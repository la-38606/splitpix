// Clipboard is the one thing HTML cannot do on its own (addendum 36.1).
document.addEventListener('click', function (event) {
	var botao = event.target.closest('[data-copiar]');
	if (!botao) {
		return;
	}
	navigator.clipboard.writeText(botao.getAttribute('data-copiar')).then(function () {
		var original = botao.textContent;
		botao.textContent = botao.getAttribute('data-copiado');
		botao.disabled = true;
		setTimeout(function () {
			botao.textContent = original;
			botao.disabled = false;
		}, 1400);
	});
});
