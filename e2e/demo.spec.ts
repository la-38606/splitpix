import { test, expect, Page } from '@playwright/test';

// The recorded walkthrough. Not a test suite: the point is a human-paced
// tour of the seeded demo group (scripts/seed-demo.sh), captured on video.
// GROUP_URL must be the seeded group's invite URL, token included.

const GROUP_URL = process.env.GROUP_URL;

// A presentation pause on top of real UI readiness — never a substitute for it.
const beat = (page: Page, ms: number) => page.waitForTimeout(ms);

async function glideTo(page: Page, selector: string) {
	await page.locator(selector).first().evaluate((el) =>
		el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
	await page.waitForTimeout(1000);
}

test('splitpix demo walkthrough', async ({ page }) => {
	test.skip(!GROUP_URL, 'GROUP_URL not set; run scripts/record-demo.sh');

	// Scene 1 — open the invite link; land on the group with balances up top.
	await page.goto(GROUP_URL!);
	await expect(page.locator('.cifra-grande')).toBeVisible();
	await expect(page.locator('.saldos li')).toHaveCount(5);
	await beat(page, 2800);

	// Scene 2 — the expense history: who paid for what.
	await glideTo(page, '.livro');
	await beat(page, 2400);

	// Scene 3 — the suggested settlement, Pix keys attached.
	await glideTo(page, '.pagamentos');
	await expect(page.locator('.pagamentos li')).toHaveCount(3);
	await beat(page, 3400);

	// Scene 4 — "Why this plan?" names the strategy and its guarantee.
	await page.locator('.plano-porque summary').hover();
	await page.locator('.plano-porque summary').click();
	await beat(page, 3200);

	// Scene 5 — "why?" beside Diego's balance: the statement behind it.
	await glideTo(page, '.saldos');
	const diegoRow = page.locator('.saldos li', { hasText: 'Diego' });
	await diegoRow.locator('.saldo-porque').hover();
	await beat(page, 400);
	await diegoRow.locator('.saldo-porque').click();
	await expect(page.locator('.extrato-saldo')).toBeVisible();
	await expect(page.locator('.livro tr')).toHaveCount(4); // 3 entries + total
	await beat(page, 4000);
	await page.locator('.link-voltar').click();

	// Scene 6 — the differentiator: compare strategies on the same balances.
	await expect(page.locator('.cifra-grande')).toBeVisible();
	await glideTo(page, '.plano-porque');
	await page.locator('.plano-porque summary').click();
	await beat(page, 600);
	await page.getByText('Comparar estratégias').hover();
	await beat(page, 500);
	await page.getByText('Comparar estratégias').click();
	await expect(page.locator('.plano-card')).toHaveCount(3);
	await beat(page, 2800);
	// Dwell on the two exact strategies: 2 payments with 2 new pairs versus
	// 3 payments with 1 — same balances, different settlement graphs.
	await page.locator('.plano-card', { hasText: 'Menos pagamentos' }).hover();
	await beat(page, 2400);
	await page.locator('.plano-card', { hasText: 'Preservar relações' }).hover();
	await beat(page, 3000);

	// Scene 7 — technical details exist, but only behind a click.
	await glideTo(page, '.detalhes-tecnicos');
	await page.locator('.detalhes-tecnicos summary').click();
	await beat(page, 3600);
	await page.locator('.detalhes-tecnicos summary').click();
	await beat(page, 500);

	// Scene 8 — back on the simple page, record a payment: the person pays
	// through their own bank app, then marks it here and the plan shrinks.
	await page.locator('.link-voltar').click();
	await expect(page.locator('.cifra-grande')).toBeVisible();
	await glideTo(page, '.pagamentos');
	const markPaid = page.locator('.botao-marcar').first(); // Ana → Diego
	await markPaid.hover();
	await beat(page, 600);
	await markPaid.click();
	await expect(page.locator('.aviso')).toBeVisible();
	await glideTo(page, '.pagamentos');
	await expect(page.locator('.pagamentos li')).toHaveCount(2);
	await beat(page, 2400);

	// Scene 9 — end on the Pix instruction: copy the recipient's key.
	const firstCopy = page.locator('.pagamento-chave .botao-copiar').first();
	await firstCopy.hover();
	await beat(page, 600);
	await firstCopy.click();
	await expect(page.locator('.pagamento-chave .botao-copiar').first())
		.toHaveText(/copiado/i);
	await beat(page, 3400);
});
