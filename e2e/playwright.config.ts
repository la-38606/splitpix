import { defineConfig } from '@playwright/test';

// Recording configuration, not a test rig: one worker, no retries, video on.
// The walkthrough is paced for human viewing (scripts/record-demo.sh).
export default defineConfig({
	testDir: '.',
	timeout: 300_000,
	retries: 0,
	workers: 1,
	reporter: [['list']],
	outputDir: 'test-results',
	use: {
		viewport: { width: 1440, height: 900 },
		video: { mode: 'on', size: { width: 1440, height: 900 } },
		// The copy buttons write to the clipboard; without this the final
		// "copiado" beat never happens in a headless context.
		permissions: ['clipboard-read', 'clipboard-write'],
		launchOptions: { slowMo: 120 },
	},
});
