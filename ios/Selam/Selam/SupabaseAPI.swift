import Foundation

struct SelamProfile: Codable {
    let username: String
    let displayName: String
    let phoneLast4: String?
    let safetyCode: String
    let profileReady: Bool?
}

struct RecoveryResult: Codable {
    let success: Bool
    let resultMessage: String
    let username: String?
    let displayName: String?
    let phoneLast4: String?
    let safetyCode: String?
}

struct SelamChat: Codable, Identifiable {
    let conversationId: UUID
    let conversationKind: String
    let username: String?
    let displayName: String?
    let lastMessage: String?
    let lastMessageAt: String?
    let archived: Bool?
    let pinned: Bool?
    let mutedUntil: String?
    var id: UUID { conversationId }
}

struct SelamMessage: Codable, Identifiable {
    let messageId: Int64
    let senderId: UUID
    let messageBody: String
    let createdAt: String
    let messageType: String?
    let fileName: String?
    var id: Int64 { messageId }
}

struct ContactMatch: Codable, Identifiable {
    let userId: UUID
    let username: String
    let displayName: String
    let matchedPhone: String
    var id: UUID { userId }
}

enum SelamAPIError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let value): return value }
    }
}

actor SupabaseAPI {
    static let shared = SupabaseAPI()
    private let baseURL = URL(string: "https://czaangjaxdffliwigcbx.supabase.co")!
    private let apiKey = "sb_publishable_O6uX6S8scE5ha7zYF_G32g_QKE_CEST"
    private let decoder: JSONDecoder = {
        let value = JSONDecoder()
        value.keyDecodingStrategy = .convertFromSnakeCase
        return value
    }()

    private var accessToken: String? {
        get { UserDefaults.standard.string(forKey: "selam_access_token") }
        set { UserDefaults.standard.set(newValue, forKey: "selam_access_token") }
    }
    private var userId: UUID? {
        get { UserDefaults.standard.string(forKey: "selam_user_id").flatMap(UUID.init) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "selam_user_id") }
    }

    func currentUserId() -> UUID? { userId }

    func ensureSession() async throws {
        if accessToken != nil, userId != nil { return }
        let data = try await request(path: "/auth/v1/signup", payload: [
            "data": [:] as [String: String],
            "gotrue_meta_security": [:] as [String: String]
        ], authorized: false)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = object["access_token"] as? String,
              let user = object["user"] as? [String: Any],
              let idText = user["id"] as? String,
              let id = UUID(uuidString: idText) else {
            throw SelamAPIError.message("Cihaz hesabı oluşturulamadı.")
        }
        accessToken = token
        userId = id
    }

    func profile() async throws -> SelamProfile {
        try decodeFirst(SelamProfile.self, from: await rpc("get_my_profile", payload: [:]))
    }

    func setup(name: String, phone: String, pin: String) async throws -> SelamProfile {
        try decodeFirst(SelamProfile.self, from: await rpc("setup_profile_with_pin", payload: [
            "new_display_name": name, "phone_e164": phone, "recovery_pin": pin
        ]))
    }

    func recover(phone: String, pin: String) async throws -> RecoveryResult {
        try decodeFirst(RecoveryResult.self, from: await rpc("recover_profile", payload: [
            "phone_e164": phone, "recovery_pin": pin
        ]))
    }

    func chats() async throws -> [SelamChat] {
        try decoder.decode([SelamChat].self, from: await rpc("list_my_chats", payload: [:]))
    }

    func messages(chatId: UUID) async throws -> [SelamMessage] {
        try decoder.decode([SelamMessage].self, from: await rpc("list_chat_messages", payload: [
            "chat_id": chatId.uuidString
        ]))
    }

    func send(message: String, chatId: UUID) async throws {
        _ = try await rpc("send_chat_message", payload: [
            "chat_id": chatId.uuidString, "message_body": message
        ])
    }

    func match(phones: [String]) async throws -> [ContactMatch] {
        try decoder.decode([ContactMatch].self, from: await rpc("match_contacts", payload: [
            "contact_phones": phones
        ]))
    }

    func startChat(with userId: UUID) async throws -> UUID {
        let data = try await rpc("start_direct_chat", payload: ["other_user_id": userId.uuidString])
        let text = try decoder.decode(String.self, from: data)
        guard let id = UUID(uuidString: text) else {
            throw SelamAPIError.message("Sohbet başlatılamadı.")
        }
        return id
    }

    static func normalizePhone(_ raw: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var digits = clean.filter(\.isNumber)
        var international = clean.hasPrefix("+")
        if digits.hasPrefix("00") { digits.removeFirst(2); international = true }
        let result: String
        if international { result = "+" + digits }
        else if digits.count == 11, digits.hasPrefix("0") { result = "+90" + digits.dropFirst() }
        else if digits.count == 10 { result = "+90" + digits }
        else if digits.count == 12, digits.hasPrefix("90") { result = "+" + digits }
        else { return nil }
        return result.range(of: "^\\+[1-9][0-9]{7,14}$", options: .regularExpression) == nil ? nil : result
    }

    private func rpc(_ name: String, payload: [String: Any]) async throws -> Data {
        try await request(path: "/rest/v1/rpc/\(name)", payload: payload, authorized: true)
    }

    private func request(path: String, payload: [String: Any], authorized: Bool) async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue(apiKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authorized, let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let message = object?["message"] as? String
                ?? object?["msg"] as? String
                ?? "Sunucu bağlantısı kurulamadı."
            throw SelamAPIError.message(message)
        }
        return data
    }

    private func decodeFirst<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let values = try decoder.decode([T].self, from: data)
        guard let first = values.first else { throw SelamAPIError.message("Sunucudan sonuç alınamadı.") }
        return first
    }
}
