// Derleme sirasinda her ikonun ekran goruntusunu alir (kontrol icin).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage({ viewport: { width: 160, height: 160 } });
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  for (const n of ['speaky', 'sparky', 'chatbot', 'ucak', 'kaykay', 'muzik']) {
    await p.goto('file://' + process.cwd() + '/app/src/main/assets/web/icon.html#' + n);
    for (const t of [0, 1]) {
      await p.waitForTimeout(700);
      await p.screenshot({ path: '/tmp/out/prev_' + n + '_' + t + '.png', omitBackground: true });
    }
  }
  await p.goto('file://' + process.cwd() + '/app/src/main/assets/web/icon.html#speaky');
  for (const st of ['yaziyor', 'dinliyor', 'hata']) {
    await p.waitForTimeout(500);
    await p.evaluate(x => window.speaky(x), st);
    await p.waitForTimeout(900);
    await p.screenshot({ path: '/tmp/out/prev_speaky_' + st + '.png', omitBackground: true });
  }
  require('fs').writeFileSync('/tmp/out/prev_errors.txt', errs.join('\n') || 'hata yok');
  await b.close();
})();
