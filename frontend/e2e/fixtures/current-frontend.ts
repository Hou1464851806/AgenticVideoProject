import { test as base, expect, type Page } from '@playwright/test';

// Exercise current UI against an existing Java deployment without rebuilding shared services.
// Keep the deployment's trusted browser Origin and pass API requests through unchanged.
export async function serveCurrentFrontend(page: Page, baseURL: string) {
  if (!process.env.E2E_UI_ORIGIN) return;
  const origin = new URL(baseURL).origin;
  await page.route(`${origin}/**`, async route => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith('/api/')) { await route.continue(); return; }
    const response = await route.fetch({ url: `${process.env.E2E_UI_ORIGIN}${url.pathname}${url.search}` });
    await route.fulfill({ response });
  });
}
export const test = base.extend({
  page: async ({ page, baseURL }, use) => {
    await serveCurrentFrontend(page, baseURL!);
    await use(page);
  },
});
export { expect };
