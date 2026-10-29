# Coding Rules

## General

- (e.g. Use as much code splitting, into components or controller as you can, the code space must be neat and clean).
- (e.g. Always use singleton pattern when possible).

  ```typescript

    // Defination
    export default class Something {
        
        public static readonly current: Something = new Something();
        private constructor() {}

        public sayHello() {}
    }


    // Usage
    Something.current.sayHello();
  ```

- Everything should be fully typed:
  ```typescript
  const name: string = "";
  const age: number = 25;
  const isActive: boolean = true;
  ```

- Acronyms in a class, file, or identifier name are written in full caps, never Pascal-cased as if they were an ordinary word:
  ```typescript
  // Wrong
  BackendApiRoutesFactory
  AdminApiClientUtility

  // Right
  BackendAPIRoutesFactory
  AdminAPIClientUtility
  ```
  This applies to the acronym itself (`API`, `URL`, `DTO`, `SSO`, etc.) wherever it appears in an identifier - at the start, middle, or end. It does not apply to literal string values like a URL path (e.g. `/Api/V1/Authentication/Login` stays exactly as the real backend casing it, since that is data, not an identifier).

---

## File Structure

- All enums, structs, interfaces, and classes used for data transfer, state options, or data representations must be stored in the `Features/${folderName}/Models/` folder inside each feature, and the file name should be in PascalCase. Feature-level `Models/` subdivides the same way as the global `Models/` folder (see below): `Interfaces/`, `Types/`, `DTOs/`, `Classes/`, `Records/`.
- Every feature folder must follow this standardized layout (e.g., if the feature is `Authentication`):
  - **TestSuites Folder**: 
    - `Features/Authentication/TestSuites/LogInAuthenticationTest.ts`
    - `Features/Authentication/TestSuites/SignUpAuthenticationTest.ts`
    - `Features/Authentication/TestSuites/ForgotPasswordAuthenticationTest.ts`
  - **Utilities Folder**: `Features/Authentication/Utilities/`, **NOTE**: This folder will contain all the utilities files, the files should have only one default exported class with singleton pattern, each file / utility will have exactly one responsibility. 
  - **Assertion Folder**: `Features/Authentication/Assertion/LogInAuthenticationAssertion.ts`
  - **Constants Folder**: 
    - `Features/Authentication/Constants/LogInAuthenticationCON.ts`, **NOTE**: This file will contain all the constants related to the Login Auth Test, like the name of the elements, buttons, inputs things like that please
  - **Models Folder**: `Features/Authentication/Models/`
    - `Features/Authentication/Models/Interfaces/*Interface.ts`
    - `Features/Authentication/Models/Types/*Type.ts`
    - `Features/Authentication/Models/DTOs/*DTO.ts`
    - `Features/Authentication/Models/Classes/*Class.ts`
    - `Features/Authentication/Models/Records/*Record.ts`

- If a file/class/utility/tool is used across more than one feature, place it in the appropriate global folder:
  - **Global TestSuites Folder**: `TestSuites/`
    - All file names must be PascalCase and end with `*Test.ts`.
  - **Global Constants Folder**: `Constants/`
    - All file names must be PascalCase and end with `*CON.ts`.
  - **Global Models Folder**: `Models/`
    - **Global Interface Folder**: `Models/Interfaces/*Interface.ts`
    - **Global Types Folder**: `Models/Types/*Type.ts`
    - **Global DTOs Folder**: `Models/DTOs/*DTO.ts`
    - **Global Classes Folder**: `Models/Classes/*Class.ts`
    - **Global Records Folder**: `Models/Records/*Record.ts`
  - **Global Utils Folder**: `Utilities/*Utility.ts`
  - **Global Helper Folder**: `Helpers/*Helper.ts` (All helper classes must follow the singleton pattern).
  - **Global Exceptions Folder**: `Exceptions/*CException.ts` (Custom exceptions).
  - **Global Validators Folder**: `Validators/*CValidator.ts` (custom) or `*SValidator.ts` (system).
    - Each validator class must be a singleton with a single `validate(...): boolean` method returning `true` or `false`.
    - Each validation class must perform only one type of validation.
  - **Global Factories Folder**: `Factories/*Factory.ts`
    - Holds the frontend page-path registry — see "Strict Page Route Factory Rules" below.

---

## Assertion Pattern (Feature UI-State Assertions)

- **Do not write raw inline `expect(...)` chains scattered across a test body for anything beyond a single, obvious check.** Instead, use the Feature Assertion Singleton class — each assertion method wraps one or more Playwright `expect()` calls behind a single, descriptively-named method.
- **File Name**: `${FeatureName}Assertion.ts` (e.g. `LogInAuthenticationAssertion.ts`, `AssetInventoryAssertion.ts`)
- **File Location**: `Features/${FeatureName}/Assertion/${FeatureName}Assertion.ts`
- **Pattern**: Singleton class with a lowercase `current` static instance (per the General singleton pattern above), methods named `assert*`.

### Assertion Definition:
```typescript
import { Page, expect } from '@playwright/test';
import LogInAuthenticationCON from '../Constants/LogInAuthenticationCON';
import ApplicationPageRoutesFactory from '../../../Factories/ApplicationPageRoutesFactory';

export default class LogInAuthenticationAssertion {
    public static readonly current: LogInAuthenticationAssertion = new LogInAuthenticationAssertion();
    private constructor() {}

    public async assertLoginPageLoaded(page: Page): Promise<void> {
        await expect(page).toHaveURL(ApplicationPageRoutesFactory.AuthenticationRoutes.Login);
        await expect(page.locator(LogInAuthenticationCON.EmailInput)).toBeVisible();
        await expect(page.locator(LogInAuthenticationCON.PasswordInput)).toBeVisible();
    }

    public async assertLoginSucceeded(page: Page): Promise<void> {
        await expect(page).toHaveURL(ApplicationPageRoutesFactory.DashboardRoutes.Overview);
    }

    public async assertLoginErrorShown(page: Page, expectedMessage: string): Promise<void> {
        await expect(page.locator(LogInAuthenticationCON.ErrorToast)).toContainText(expectedMessage);
    }
}
```

### TestSuite Usage:
```typescript
import { test } from '@playwright/test';
import LogInAuthenticationAssertion from '../Assertion/LogInAuthenticationAssertion';
import ApplicationPageRoutesFactory from '../../../Factories/ApplicationPageRoutesFactory';

test('logs in successfully with valid admin credentials', async ({ page }) => {
    await page.goto(ApplicationPageRoutesFactory.AuthenticationRoutes.Login);
    await LogInAuthenticationAssertion.current.assertLoginPageLoaded(page);

    // ... fill the form, submit ...

    await LogInAuthenticationAssertion.current.assertLoginSucceeded(page);
});
```

---

## Strict Page Route Factory Rules

- Never hardcode frontend page paths/URLs directly inside a test spec.
- All navigated-to page paths must reference the singleton `ApplicationPageRoutesFactory.ts` located in `Factories/`, grouped by feature.

```typescript
// Wrong
await page.goto('/login');

// Right
await page.goto(ApplicationPageRoutesFactory.AuthenticationRoutes.Login);
```

### Factory Definition:
```typescript
export default class ApplicationPageRoutesFactory {
    public static readonly AuthenticationRoutes = {
        Login: '/login',
        SignUp: '/signup',
        ForgotPassword: '/forgot-password',
    } as const;

    public static readonly DashboardRoutes = {
        Overview: '/dashboard',
    } as const;
}
```

---

## Guaranteed Cleanup For Production-Creating Tests

- These tests run against the real, live production environment - not a disposable test environment. Any test that creates a real resource (a user, an asset, an employee, a device service request, anything) **must** remove or neutralize it afterward, so it never lingers and shows up on the production side for a real user to see.
- Cleanup must happen through the same real backend workflow a human admin/user would use to remove that kind of resource (e.g. the actual `RejectUser`/`DeleteAsset`/`DeleteEmployee` endpoint), called directly via the API (not through the UI) using a dedicated Utility in that feature's `Utilities/` folder - never a special "test-only" backdoor.
- **Cleanup must run no matter what** - if any assertion after the resource is created throws, the test still fails (that's correct), but the cleanup call must still execute. Capture the created resource's id as soon as it is known, then wrap everything from that point onward in `try { ... } finally { await cleanup(id); }`:
  ```typescript
  const createdId = /* ...from the create response... */;

  try {
    // assertions that could throw
  } finally {
    await SomeCleanupUtility.current.deleteSomething(request, createdId);
  }
  ```
- Never place a cleanup call as the last line of a sequential test body with no `try/finally` - if an earlier assertion fails, that line is never reached and the resource is left behind in production permanently.
