-- The scrolling "install the APK" banner on the website (shown only outside the Android app), editable
-- from the admin panel instead of being fixed in code. Defaults match the banner's current fixed text.
ALTER TABLE app_settings ADD COLUMN IF NOT EXISTS apk_banner_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE app_settings ADD COLUMN IF NOT EXISTS apk_banner_text TEXT NOT NULL DEFAULT '📲 Install the Panchayat app (APK) from panchayat.world/download';
ALTER TABLE app_settings ADD COLUMN IF NOT EXISTS apk_banner_soon TEXT NOT NULL DEFAULT 'Coming soon on Google Play';
