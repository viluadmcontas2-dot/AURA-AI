# Matriz de testes (tudo precisa estar verde para liberar APK)

Legenda: [ ] pendente, [x] verde com evidência em docs/REGISTRO.md.

## A. Sem Termux e sem motor (modo morto proibido)
- [ ] Abrir o app limpo: não fecha, mostra Início com banner "Motor desligado" e botão "Iniciar o motor".
- [ ] Cada uma das 4 abas (Início, Projetos, Dispositivos, Atividade) abre e mostra conteúdo local ou estado vazio com ação.
- [ ] Ver, criar, editar e apagar projeto sem motor (salvo no aparelho).
- [ ] Rascunho do chat sobrevive a fechar e abrir o app.
- [ ] Ajustes abre todas as seções; ações que precisam do motor mostram erro com correção.
- [ ] Enviar pedido sem motor: fica na fila local com aviso claro, sem perder o texto.
- [ ] Voz: com e sem reconhecedor, com e sem permissão.

## B. Termux e motor
- [ ] Onboarding: cada passo com estado real (instalado, permissão, comandos externos, motor).
- [ ] "Iniciar o motor" por RUN_COMMAND; falha mostra o motivo e o comando para colar.
- [ ] Checkup: cada linha com estado e botão de correção.
- [ ] Contas: Entrar mostra link/código ou o motivo da falha.

## C. Permissões
- [ ] Pedido na abertura por versão do Android; negado duas vezes leva às configurações do app.
- [ ] Tela de permissões dentro do app lista o que falta.

## D. Telas do design (45 em docs/design/telas)
- [ ] Todas existem, abrem e batem com o .dc.html (checklist por tela em docs/REGISTRO.md).
- [ ] Reformar app (4 telas), Checkup, Memória, Notificações, Capacidades (lista/detalhe), Conexões, Intercorrências, Bastidores.

## E. Robustez
- [ ] Rotação, voltar, teclado, segundo plano e retomada em todas as telas.
- [ ] Nenhum toque sem efeito visível (teste automático que clica em todo elemento clicável).
- [ ] Crash capturado e mostrado na próxima abertura.

## F. Atualização
- [ ] APK novo instala por cima do anterior (mesma assinatura, versionCode maior) sem perder dados nem login.
