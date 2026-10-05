# Task 03: UI Reads Images from the API; Remove OriginalApp

**Type:** Code Modification

## Goal

The claim detail page shows images from the backend API, and claims without images show a clear
message. Remove the legacy `OriginalApp` page and the assets nothing uses any more.

## What to Do

- `ClaimDetail.tsx`:
  - Fetch `GET /api/db/claims/{id}/images`, and stop building `original_images` / `processed_images` from the claim id.
  - "Attached images" (Documents tab) shows the `ORIGINAL` images.
  - The right-hand panel shows `PROCESSED` images if there are any, otherwise the `ORIGINAL` ones, otherwise "No images attached".
- `ImageCarousel.tsx`: accept full image URLs from the API instead of building `/images/<image_key>`.
- Delete `OriginalApp.tsx`, its route in `routes.tsx`, and the asset files task 02 found unreferenced.
  Remove any webpack rule that existed only for those assets, if it's no longer needed.
- **Playwright tests:**
  - A seeded claim shows its original and processed images, served from the API (check the `src` attribute points to the API).
  - A claim with no images shows "No images attached" in both places. Create the claim in the test and delete it afterwards.
  - The `/OriginalApp` route is gone.

## Files/Areas

- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx`
- `src/main/webui/src/app/components/ImageCarousel/ImageCarousel.tsx`
- `src/main/webui/src/app/components/OriginalApp/OriginalApp.tsx` (delete), `src/main/webui/src/app/routes.tsx`
- `src/main/webui/src/app/assets/images/` (delete unused files), `src/main/webui/webpack.common.js` (if needed)
- `src/test/java/org/parasol/ui/ClaimsDetailPageTests.java` (and/or a new image UI test)

## Key Points

- `config.backend_api_url` is how the UI reaches the API. Tests run on port 8081 (`BACKEND_API_URL` set in `pom.xml` surefire/failsafe).
- Check that `index.html`'s favicon (`/images/favicon.svg`, copied by `CopyPlugin`) still works after any webpack change.

## Done When

- [x] No frontend code builds image paths from the claim id.
- [x] `OriginalApp.tsx`, its route and the unused assets are gone, and `./mvnw -B clean package -DskipTests -Pollama` builds the frontend.
- [x] The Playwright tests above pass.