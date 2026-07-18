# Metric Platform Logout Button Design

## Goal

Add an obvious logout control to the authenticated Metric Platform interface so a user can clear the current browser session and return to the login page. This change applies only to Metric Platform.

## User Experience

- The right side of the main page header shows the authenticated account display name and a `登出` button.
- Clicking `登出` removes the Metric Platform authentication session from `sessionStorage`.
- The browser immediately navigates to `/login?reason=required`.
- The login page remains responsible for accepting the next username and password.

## Components and Data Flow

- `PlatformLayout` reads the current identity from the existing `getAuthSession()` helper.
- The header renders the identity display name when a valid local session exists.
- The logout handler calls the existing `clearAuthSession()` helper and uses React Router navigation to replace the current history entry with the login route.
- No backend logout endpoint is added. Tokens remain opaque and expire after ten hours; logout ends the browser session by removing its stored token.

## Error Handling

- If local session data is missing or malformed, the account name is omitted while the logout control remains available.
- Clearing a missing session is safe and still redirects to the login page.

## Testing

- Extend the existing `PlatformLayout` component test before implementation.
- Verify the authenticated account name and logout button render.
- Verify clicking `登出` clears the stored session and navigates to the login route with `reason=required`.
- Run the complete frontend test suite and production build.
- Perform a browser check against the local Metric Platform frontend.

## Out of Scope

- Server-side token revocation or a logout API.
- Account settings or an account dropdown menu.
- Any change to Data Agent.
