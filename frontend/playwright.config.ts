import { defineConfig, devices } from "@playwright/test";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const UI_URL = "http://localhost:3000";

/**
 * Browser smoke tests: the production build of the dashboard against the backend's `standalone` profile (no Kafka,
 * Redis or PostgreSQL). Build both first: `mvn -B package -DskipTests` in backend/ and `npm run build` here.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: { baseURL: UI_URL, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "java -jar ../backend/target/metromind-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=standalone",
      url: `${API_URL}/actuator/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command: "npm run start",
      url: UI_URL,
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});
