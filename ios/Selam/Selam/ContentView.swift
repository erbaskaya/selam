import Contacts
import SwiftUI

@MainActor
final class SelamStore: ObservableObject {
    enum State { case loading, onboarding, ready }
    @Published var state: State = .loading
    @Published var profile: SelamProfile?
    @Published var error = ""
    let api = SupabaseAPI.shared

    func boot() async {
        do {
            try await api.ensureSession()
            let value = try await api.profile()
            profile = value
            state = value.profileReady == true ? .ready : .onboarding
        } catch { self.error = error.localizedDescription; state = .onboarding }
    }

    func setup(name: String, phone: String, pin: String) async -> Bool {
        guard let normalized = SupabaseAPI.normalizePhone(phone), valid(pin) else {
            error = "Geçerli telefon ve zor bir 6 haneli PIN yazın."; return false
        }
        do { profile = try await api.setup(name: name, phone: normalized, pin: pin); state = .ready; return true }
        catch { self.error = error.localizedDescription; return false }
    }

    func recover(phone: String, pin: String) async -> Bool {
        guard let normalized = SupabaseAPI.normalizePhone(phone), pin.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
            error = "Telefon numaranızı ve 6 haneli PIN'inizi yazın."; return false
        }
        do {
            let result = try await api.recover(phone: normalized, pin: pin)
            guard result.success, let username = result.username,
                  let displayName = result.displayName, let safety = result.safetyCode else {
                error = result.resultMessage; return false
            }
            profile = SelamProfile(username: username, displayName: displayName,
                                   phoneLast4: result.phoneLast4, safetyCode: safety, profileReady: true)
            state = .ready
            return true
        } catch { self.error = error.localizedDescription; return false }
    }

    private func valid(_ pin: String) -> Bool {
        pin.range(of: "^[0-9]{6}$", options: .regularExpression) != nil
            && !["000000", "111111", "123456", "654321"].contains(pin)
    }
}

struct ContentView: View {
    @StateObject private var store = SelamStore()

    var body: some View {
        Group {
            switch store.state {
            case .loading: ProgressView("Cihaz hesabın hazırlanıyor…")
            case .onboarding: OnboardingView(store: store)
            case .ready: MainTabs(store: store)
            }
        }
        .task { if store.state == .loading { await store.boot() } }
    }
}

struct OnboardingView: View {
    @ObservedObject var store: SelamStore
    @State private var name = ""
    @State private var phone = ""
    @State private var pin = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 8) {
                        Text("🤝").font(.system(size: 62))
                        Text("Selam'a hoş geldin").font(.title.bold())
                        Text("SMS ücreti yok. 6 haneli PIN'in hesabını yeniden kurduğunda geri getirir.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity).padding(.vertical)
                }
                Section("Yeni hesap") {
                    TextField("Adınız ve soyadınız", text: $name)
                    TextField("Telefon (05xx xxx xx xx)", text: $phone).keyboardType(.phonePad)
                    SecureField("6 haneli kurtarma PIN'i", text: $pin).keyboardType(.numberPad)
                    Button("Devam et") { Task { busy = true; _ = await store.setup(name: name, phone: phone, pin: pin); busy = false } }
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).count < 2)
                }
                Section("Uygulamayı yeniden kurduysanız") {
                    Button("Hesabımı geri yükle") { Task { busy = true; _ = await store.recover(phone: phone, pin: pin); busy = false } }
                        .disabled(busy)
                }
                if !store.error.isEmpty { Section { Text(store.error).foregroundStyle(.red) } }
            }
        }
    }
}

struct MainTabs: View {
    @ObservedObject var store: SelamStore
    var body: some View {
        TabView {
            NavigationStack { ChatListView(store: store) }
                .tabItem { Label("Sohbetler", systemImage: "message.fill") }
            PlaceholderView(title: "Güncellemeler", icon: "circle.dashed")
                .tabItem { Label("Güncellemeler", systemImage: "circle.dashed") }
            PlaceholderView(title: "Topluluklar", icon: "person.3.fill")
                .tabItem { Label("Topluluklar", systemImage: "person.3.fill") }
        }
    }
}

struct ChatListView: View {
    @ObservedObject var store: SelamStore
    @State private var chats: [SelamChat] = []
    @State private var showNewChat = false
    @State private var error = ""

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            List(chats.filter { $0.archived != true }) { chat in
                NavigationLink {
                    ChatView(api: store.api, chat: chat)
                } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(chat.displayName?.isEmpty == false ? chat.displayName! : "@\(chat.username ?? "selam")").bold()
                        Text(chat.lastMessage ?? "Yeni sohbet").foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            if chats.isEmpty { ContentUnavailableView("Henüz sohbet yok", systemImage: "message", description: Text("＋ düğmesinden rehberindeki Selam kullanıcılarını bul.")) }
            Button { showNewChat = true } label: {
                Image(systemName: "plus").font(.title2.bold()).foregroundStyle(.white)
                    .frame(width: 58, height: 58).background(Color.accentColor, in: Circle()).shadow(radius: 5)
            }
            .accessibilityLabel("Yeni sohbet başlat")
            .padding(20)
        }
        .navigationTitle("Selam")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Menu { Button("Yeni sohbet") { showNewChat = true } } label: { Image(systemName: "ellipsis.circle") } } }
        .sheet(isPresented: $showNewChat) { NewChatView(api: store.api) { showNewChat = false; Task { await load() } } }
        .task { await load() }
        .refreshable { await load() }
        .alert("Selam", isPresented: .constant(!error.isEmpty)) { Button("Tamam") { error = "" } } message: { Text(error) }
    }

    private func load() async {
        do { chats = try await store.api.chats() } catch { self.error = error.localizedDescription }
    }
}

struct NewChatView: View {
    let api: SupabaseAPI
    let completed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var matches: [ContactMatch] = []
    @State private var names: [String: String] = [:]
    @State private var status = "Rehber taranıyor…"

    var body: some View {
        NavigationStack {
            List(matches) { person in
                Button {
                    Task { _ = try? await api.startChat(with: person.userId); completed(); dismiss() }
                } label: {
                    VStack(alignment: .leading) {
                        Text(names[person.matchedPhone] ?? person.displayName).foregroundStyle(.primary)
                        Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .overlay { if matches.isEmpty { ContentUnavailableView(status, systemImage: "person.crop.circle.badge.questionmark") } }
            .navigationTitle("Yeni sohbet")
            .toolbar { Button("Kapat") { dismiss() } }
            .task { await scan() }
        }
    }

    private func scan() async {
        let store = CNContactStore()
        do {
            let allowed = try await store.requestAccess(for: .contacts)
            guard allowed else { status = "Rehber izni verilmedi."; return }
            var phones: [String] = []
            let request = CNContactFetchRequest(keysToFetch: [CNContactGivenNameKey as CNKeyDescriptor,
                                                               CNContactFamilyNameKey as CNKeyDescriptor,
                                                               CNContactPhoneNumbersKey as CNKeyDescriptor])
            try store.enumerateContacts(with: request) { contact, _ in
                for number in contact.phoneNumbers {
                    if let normalized = SupabaseAPI.normalizePhone(number.value.stringValue) {
                        phones.append(normalized)
                        names[normalized] = "\(contact.givenName) \(contact.familyName)".trimmingCharacters(in: .whitespaces)
                    }
                }
            }
            matches = try await api.match(phones: Array(Set(phones)).prefix(2000).map { $0 })
            status = "Rehberinizde henüz Selam kullanan kişi yok."
        } catch { status = error.localizedDescription }
    }
}

struct ChatView: View {
    let api: SupabaseAPI
    let chat: SelamChat
    @State private var messages: [SelamMessage] = []
    @State private var text = ""

    var body: some View {
        VStack(spacing: 0) {
            List(messages) { message in
                HStack {
                    Text(message.messageType == "file" ? "📎 \(message.fileName ?? message.messageBody)" : message.messageBody)
                        .padding(10).background(.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                }
            }
            HStack {
                TextField("Mesaj", text: $text).textFieldStyle(.roundedBorder)
                Button("Gönder") { Task { let body = text; text = ""; try? await api.send(message: body, chatId: chat.id); await load() } }
                    .buttonStyle(.borderedProminent).disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            }.padding()
        }
        .navigationTitle(chat.displayName ?? chat.username ?? "Sohbet")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async { messages = (try? await api.messages(chatId: chat.id)) ?? messages }
}

struct PlaceholderView: View {
    let title: String
    let icon: String
    var body: some View { NavigationStack { ContentUnavailableView(title, systemImage: icon).navigationTitle(title) } }
}
