import { handle } from './worker.mjs';
Deno.serve(request => handle(request, {
  SUPABASE_URL: Deno.env.get('SUPABASE_URL'),
  SUPABASE_SERVICE_ROLE_KEY: Deno.env.get('SUPABASE_SERVICE_ROLE_KEY'),
  FIREBASE_SERVICE_ACCOUNT: Deno.env.get('FIREBASE_SERVICE_ACCOUNT'),
}));
