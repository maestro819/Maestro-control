# Maestro Device Control

Starter project for a permission-based device control system.

## Architecture
- `web-controller/` — controller dashboard intended for Vercel.
- `android-target/` — Android target app starter/architecture notes.
- `supabase/` — database schema and security policies.

## Important privacy rule
Camera and microphone are only used through Android's permission-controlled APIs and visible user-approved flows. The project does not bypass Android privacy indicators or secretly activate sensors.

## Next steps
1. Create a GitHub repository.
2. Upload this project.
3. Create the Supabase project.
4. Run `supabase/schema.sql`.
5. Deploy `web-controller` to Vercel.
6. Build the Android target app.
