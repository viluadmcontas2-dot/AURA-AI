# Fluxos de uso — diagnóstico e plano

Análise estática em 28/10/2026. **Plano, não implementação nem atestado de testes.** O relato do celular orienta as prioridades; não foi reproduzido aqui. Só este documento pode mudar nesta tarefa; registro, commits e integração ficam com o coordenador.

## Leitura rápida e decisões

- Prioridade: impedir instalações sem consentimento; corrigir identidade/histórico das sessões; explicar trabalho em andamento; unificar voz e criação de aplicativo.
- Preservar: conversas feitas fora do AURA aparecem em Sessões. O motor lê os arquivos do Claude sem alterá-los (`engine/aura_engine/sessions.py:1`, `sessions.py:181`).
- AURA é a ponte para Claude Code no Termux. Texto comum vai diretamente ao Claude; comandos `/` são locais. Nada de classificador ou Gemini intermediário. Gemini fica exclusivamente no botão opcional **“Melhorar pedido”**, sem API paga.
- Voz apenas preenche o campo atual: `SpeechRecognizer`, resultados parciais, pulso, segundo toque/silêncio encerram escuta. Não muda de tela, não envia sozinho.
- Toda tela abre sem motor/internet/Termux. Dados locais aparecem primeiro; ausência de conexão não significa lista vazia. Toda falha oferece uma ação.
- Interface usa tokens e referências de `docs/design/`; um botão principal por estado, alvos ≥44 dp, foco visível e leitura acessível. As decisões acima substituem os antigos fluxos de voz separados.
- **Pré-requisito externo:** atualização por cima com mesma assinatura, `versionCode` maior e preservação de dados/login. Outra frente cuida disso; não alterar build/assinatura nestes pacotes. APK só após matriz completa verde.

**Como localizar as referências:** nomes `.kt` abaixo estão em `android/app/src/main/java/ai/aura/app/`: telas em `ui/screens/<área>/`, componentes em `ui/components/`, navegação em `ui/nav/`, integração em `integration/`, voz em `voice/`. `HomeScreen.kt` fica diretamente em `ui/screens/`; `.py` ficam em `engine/aura_engine/`. Cada referência aponta a linha inicial relevante, não a uma execução comprovada.

**Documentos antigos divergentes:** `docs/MAPA_DE_TELAS.md` ainda chama Conexões de órfã, mas `Routes.kt:199` já monta a tela. Permissões, Memória e Capacidades continuam sem corpo de rota (`Routes.kt:186`, `Routes.kt:204`). `docs/FLUXO_DA_MENSAGEM.md` admite fallback para Gemini/agy e pedidos naturais de estado locais; nesta tarefa prevalece texto comum direto ao Claude e Gemini só para melhorar pedido.

## 1. Primeiro uso / onboarding

**O que uma pessoa leiga vê hoje**

1. A abertura consulta `onboarding_done` e grava ao terminar (`MainActivity.kt:159`). Boas-vindas → Termux → Contas (`onboarding/OnboardingFlow.kt:139`), com “Pular por agora” já disponível (`OnboardingFlow.kt:310`). Há estados separados para Termux instalado, permissão, vínculo e motor funcionando (`OnboardingFlow.kt:75`).
2. O preparo pede liberar comandos externos no Termux por uma linha de terminal (`OnboardingFlow.kt:66`). Contas incluem Claude, Codex, Google e GitHub (`OnboardingFlow.kt:90`).
3. Retomar o fluxo pode conectar e iniciar; a verificação pode instalar/reinstalar o motor (`onboarding/TermuxFlow.kt:75`, `TermuxFlow.kt:180`).

**Onde trava ou confunde:** muitos pré-requisitos antes de entender o benefício; “conectar”, “instalar”, “entrar na conta” e “iniciar” se misturam. Contas aceita Claude, Codex ou Google como cérebro pronto (`OnboardingFlow.kt:356`), embora a decisão atual exija Claude para conversar. Reparo automático pode baixar pacotes. Não basta haver botões de permissão Android: isso não autoriza instalação.

**Como deve ser**

1. “O AURA ajuda você a criar e cuidar dos seus aplicativos.” Principal **“Preparar assistente”**; secundário **“Explorar aplicativo”**. Explorar libera todas as abas e rascunhos.
2. Mostrar uma etapa por vez: “Instalar Termux”, “Conectar Termux”, “Preparar motor”, “Entrar no Claude”. Explicar: “O Termux executa o assistente neste celular.” Toda instalação passa pelo fluxo 7.
3. Antes da permissão: “Permita que o AURA inicie o assistente no Termux.” Negativa: “Você pode continuar usando o app. Para conversar, conecte o Termux.” **“Abrir Conexões”**.
4. Verificação: “Verificando conexão… · {tempo}”; após 10 s: “Ainda não consegui verificar.” **“Tentar de novo”** e **“Continuar depois”**. Sucesso só após resposta real: “Assistente pronto.” **“Escrever primeiro pedido”**. Comandos, versão e diagnóstico em **“Detalhes”**.

**Critério de aceitação:** Robolectric nega cada permissão e ainda abre todas as abas, preservando rascunho após recriação. Voltar do Termux/reabrir o app não inicia downloads nem marca conexão como pronta sem evidência. Relógio falso força timeout e exibe recuperação, sem espera infinita.

**Mudanças no motor:** C3 separa diagnóstico de instalação. Antes de existir motor, o instalador local usa o mesmo manifesto/consentimento C4; nenhum endpoint é pré-requisito para renderizar onboarding.

## 2. Início

**O que uma pessoa leiga vê hoje**

1. O rascunho é lido do disco e escrito a cada tecla (`HomeScreen.kt:64`, `HomeScreen.kt:69`). Abrir sessão/trabalho muda `ChatEntry` (`HomeScreen.kt:78`).
2. “Criar aplicativo” preenche um rascunho de chat; “Controlar Android” também (`home/HomeContent.kt:205`). Esse primeiro caminho difere do assistente em Projetos.
3. O conteúdo inicial depende de `current != null` (`HomeScreen.kt:112`); a leitura local anterior não trata a falha nessa função.

**Onde trava ou confunde:** dois significados para criar aplicativo. Falha de leitura pode deixar o conteúdo inicial ausente; gravações paralelas do rascunho podem terminar fora de ordem. Abrir chat sem ID explícito pode recuperar a sessão salva (`ChatController.kt:362`), surpreendendo quem esperava conversa nova.

**Como deve ser:** mostrar sempre “O que você quer fazer?” e o campo. Principal **“Enviar pedido”**; atalhos secundários **“Criar aplicativo”**, **“Reformar apps”**, **“Ver sessões”**. Criar abre o mesmo formulário de Projetos. Se houver conversa anterior: “Última conversa: {título}” / **“Continuar conversa”**; novo pedido fora dela cria outra identidade. Motor parado: “Seu pedido ficará guardado até o assistente ligar.” / **“Iniciar o motor”**. Falha local: “Não consegui recuperar o rascunho. Você pode escrever outro.” / **“Tentar recuperar”**. Detalhes guarda diagnóstico, nunca o lugar da ação principal.

**Critério de aceitação:** com disco falhando e gateway sempre lançando erro, Início mantém campo, navegação e ações. Os dois atalhos “Criar aplicativo” chegam ao mesmo fluxo sem envio automático. Escritas de rascunho atrasadas não substituem texto mais recente.

**Mudanças no motor:** nenhuma rota exclusiva; usar C1 para envio e C3 para estado. Escolha de próxima ação é local e baseada em estado confirmado.

## 3. Chat e “está pensando ou travou?”

**O que uma pessoa leiga vê hoje**

1. Enviar adiciona a bolha imediatamente; `/` navega localmente (`ChatController.kt:101`, `ChatController.kt:118`). Texto comum chama `startChat` com sessão atual (`ChatController.kt:210`).
2. Aparece “Pensando…” com pulso; ferramentas ganham frases curtas (`chat/ChatParts.kt:130`, `ChatParts.kt:179`). Há **Parar** no campo (`chat/ChatScreen.kt:108`).
3. Motor indisponível guarda mensagens e reenvia automaticamente em lote (`ChatController.kt:283`, `ChatController.kt:310`, `ChatController.kt:325`). A integração combina eventos com consulta periódica do estado (`integration/RealEngineGateway.kt:491`).
4. “Melhorar pedido” aparece acima de 160 caracteres, oferece desfazer e não envia sozinho (`chat/ImprovePrompt.kt:40`, `ImprovePrompt.kt:61`).

**Onde trava ou confunde**

- Não há tempo decorrido/fase confirmada: a CLI pode ficar sem produzir linha até o limite de 1 h (`chat.py:21`, `chat.py:342`). Um HTTP funcionando não prova que Claude está avançando.
- Parar remove o estado visual antes da confirmação; erro ao parar é engolido (`ChatController.kt:132`). Pode parecer parado enquanto ainda executa.
- Reconexão/retry sem chave persistida pode repetir execução: `ChatManager.start` cria novo `chat_id` e mensagem a cada chamada (`chat.py:126`).
- O motor ainda aceita fallback para outros executores (`chat.py:238`); melhorar pedido usa API Gemini com chave (`sessions_http.py:60`). Ambos precisam obedecer às decisões atuais, sem cobrar API.

**Como deve ser**

1. Campo vazio: “Conte o que você quer fazer.” Ao enviar: “Enviando pedido…”; aceito: “Claude está trabalhando · {mm:ss}”. Mostrar fase somente se conhecida: “Lendo arquivos”, “Editando arquivos”, “Preparando resposta”.
2. Recebendo sinal do motor sem saída nova: “Ainda trabalhando · {mm:ss}”. Após 15 s sem sinal: “Não recebo notícias do motor há 15 s. O trabalho pode continuar no Termux.” Principal **“Verificar andamento”**; secundários **“Continuar esperando”**, **“Parar resposta”**. Não inventar percentual ou prazo.
3. Parar: “Pedindo para parar…”; confirmar: “Resposta interrompida. O texto recebido foi guardado.” Sem confirmação em 10 s: “Ainda não confirmei a parada.” / **“Verificar andamento”**. Nunca dizer que efeitos já realizados foram desfeitos.
4. Sem motor: “Pedido guardado nesta conversa. Será enviado quando o motor ligar.” / **“Iniciar o motor”**, secundário **“Cancelar envio”**. Cada mensagem mantém identidade/ordem; não juntar pedidos silenciosamente.
5. Falha: “Não consegui concluir a resposta. Seu pedido e o texto recebido estão guardados.” / **“Tentar de novo”**, consultando a execução anterior antes de repetir. Ao terminar: resposta e próximo passo contextual, sem enviar outro pedido sozinho. IDs, executor e eventos sanitizados em **“Detalhes”**.
6. Melhorar: **“Melhorar pedido”** → “Melhorando pedido…” → “Confira o texto antes de enviar.” / **“Desfazer melhoria”**. Erro mantém original: “Não consegui melhorar agora. Você pode enviar seu texto.” / **“Tentar de novo”**.

**Critério de aceitação:** relógio falso testa fases, 15 s sem sinal e parada sem confirmação; nenhum estado fica mudo. Repetir envio com a mesma identidade e reconectar produz uma execução e uma bolha por mensagem. `/` não chama modelo, texto comum chama Claude sem classificador/fallback e melhoria não envia chat nem usa API paga. Sair/reabrir acompanha o mesmo trabalho e preserva texto parcial.

**Mudanças no motor:** C1 define envio idempotente, estado/heartbeat (sinal periódico), recuperação e parada; C6 mantém melhoria opcional. O sinal deve vir de temporizador independente da leitura bloqueante da CLI e cessar em estado terminal.

## 4. Sessões e histórico incompleto

**O que uma pessoa leiga vê hoje**

1. Sessões consulta conversas e processos; se falhar, lê a lista salva (`sessions/SessionsController.kt:61`). Tocar entrega ID ao Início (`Routes.kt:207`, `HomeScreen.kt:85`). Há renomear, esconder e parar (`sessions/SessionsScreen.kt:232`).
2. O chat restaura o disco, depois tenta histórico de sessão e, se vazio/erro, histórico de chats (`ChatController.kt:344`, `ChatController.kt:388`).
3. Motor mescla lista do banco com arquivos do Claude; para mensagens prefere o arquivo se válido (`sessions.py:181`, `sessions.py:227`). Isso recupera conversas do terminal — preservar.

**Onde trava ou confunde — fatos e hipóteses**

- **Fato:** cache único `chat-history`, limitado a 200 mensagens, com ID salvo separadamente; pendências também globais (`chat/ChatModels.kt:48`, `ChatModels.kt:60`, `ChatModels.kt:70`). Trocar sessão pode sobrescrever o cache com vazio antes da leitura remota (`ChatController.kt:351`). Lista local não significa histórico local de todas as sessões.
- **Fato:** API Android pede até 500 mensagens (`AuraEngineClient.kt:131`). Leitor do motor examina apenas os primeiros 8 MiB e retorna uma cauda desse trecho; não avisa truncamento nem oferece cursor (`sessions.py:16`, `sessions.py:37`, `sessions.py:239`). Os IDs são renumerados a partir de 1 em cada recorte.
- **Fato:** motor pode trocar `cli_session` mantendo ID do app ao falhar `--resume` (`chat.py:375`). A leitura procura arquivo pelo ID original (`sessions.py:232`), podendo preferir histórico antigo ao banco mais recente. O app também abandona sessão em diversos 4xx, não só erro específico de sessão (`ChatController.kt:450`).
- **Risco a reproduzir:** `remember(gateway)` não usa identidade de entrada (`ChatScreen.kt:64`). Se a composição sobreviver à troca, o controlador pode continuar na sessão anterior. Não afirmar que esta é a causa única do relato.
- **Fato:** “nova conversa” da rota Sessões só abre Início (`Routes.kt:210`); não solicita explicitamente nova identidade. Erro de histórico pode virar “motor desligado” mesmo para sessão ausente (`ChatController.kt:379`).

**Como deve ser**

1. Abrir mostra imediatamente título, origem “No AURA”/“No terminal”, trecho guardado e “Atualizando conversa…”. Cache, rascunho, pendências e execução são separados por ID; troca cancela apenas observação da tela, não trabalho remoto.
2. Conversa grande abre no final; **“Carregar mensagens anteriores”** busca páginas sem duplicar ou renumerar IDs. “Mostrando mensagens guardadas neste celular.” distingue cache de histórico completo.
3. Sem cache: “Esta conversa ainda não foi guardada neste celular.” / **“Iniciar o motor”**. Erro de leitura: “Não consegui carregar esta conversa.” / **“Tentar de novo”**; sessão ausente: “Não encontrei esta conversa no Termux.” / **“Voltar às sessões”**.
4. **“Criar conversa”** cria identidade nova e preserva a antiga. Falha ao retomar: “Não consegui continuar esta conversa. O histórico foi preservado.” / **“Tentar retomar”**; secundário **“Criar outra conversa”**, com aviso de que o contexto não será transferido automaticamente.
5. **“Renomear conversa”**, **“Esconder conversa”** e **“Parar resposta”** permanecem. Esconder informa “A conversa continua no Claude Code.” Vazio: “Nenhuma conversa por aqui ainda.” / **“Criar conversa”**. Processos ficam em **“Detalhes”**; não misturar programa rodando com conversa.

**Critério de aceitação:** A→B→A, inclusive retorno atrasado de B, mantém título/ID/texto e envio corretos em Compose; repetir após recriação e offline. Motor testa sessão externa, arquivo >8 MiB, >500 mensagens e alias de CLI sem perder finais nem duplicar páginas. Erros 409/422 não criam conversa automaticamente; falha de resume preserva ID e apresenta escolha. Esconder nunca altera arquivos do Claude.

**Mudanças no motor:** C2 fornece identidade canônica, origem, paginação estável e indicação de completude. Remover troca silenciosa de sessão; mesclar fontes por IDs de origem, nunca por igualdade de texto, preservando mensagens iguais legítimas.

## 5. Voz no campo

**O que uma pessoa leiga vê hoje**

1. Microfone do chat usa `LocalVoiceInput` e só recebe resultado final (`ChatScreen.kt:107`); o gancho abre outra Activity via `RecognizerIntent` (`MainActivity.kt:61`).
2. Também há telas ouvir/compacta/triagem (`voice/VoiceRoutes.kt:74`, `VoiceRoutes.kt:95`, `VoiceRoutes.kt:135`). Esse caminho pode passar por `planVoiceRequest` (`VoiceRoutes.kt:43`).
3. Já existe porta com resultados parciais (`voice/SpeechRecognizerPort.kt:17`) e implementação Android; ela reinicia após resultado/silêncio (`voice/AndroidSpeechRecognizer.kt:85`). `VoiceSession` pode enviar após 1,5 s (`voice/VoiceSession.kt:107`).

**Onde trava ou confunde:** duas mecânicas distintas para falar, nenhuma entrega o comportamento único decidido. Rede do reconhecedor é diferente de conexão com motor; chamar ambas de “desconectado” não explica a correção. Detectar Activity de voz não equivale a verificar disponibilidade de `SpeechRecognizer` (`AndroidSpeechRecognizer.kt:25`).

**Como deve ser:** tocar **“Ditar mensagem”** pede microfone quando necessário e inicia ali: “Ouvindo… Toque para parar.” Pulso no ícone, transcrição parcial no campo, texto anterior preservado. Segundo toque ou silêncio encerra, sem envio: “Texto pronto. Confira e envie.” Botão **“Enviar pedido”**. Sem fala: “Não ouvi nenhuma palavra.” / **“Tentar ditar”**. Negativa: “Para ditar, permita o microfone. Você pode continuar digitando.” / **“Permitir microfone”**; bloqueio permanente: **“Abrir permissões”**. Reconhecedor indisponível: “A digitação por voz não está disponível neste celular.” / **“Usar teclado”**. Falha de rede: “Não consegui reconhecer a fala sem internet.” / **“Tentar ditar”**. Detalhes identifica o serviço; nunca pedir instalar pacote automaticamente.

**Critério de aceitação:** porta falsa em Compose entrega parciais/final sem duplicar palavras ou mudar rota; segundo toque e silêncio liberam microfone sem enviar. Permissão negada, serviço ausente, rede falhando e saída de tela preservam texto e permitem teclado. Motor desligado não impede iniciar reconhecimento disponível; rotação encerra escuta e mantém rascunho, sem reinício automático.

**Mudanças no motor:** nenhuma para ditado; texto confirmado usa C1. Remover o uso de triagem no caminho da voz; não criar endpoint de áudio, interpretação ou transcrição.

## 6. Conexões e Dispositivos

**O que uma pessoa leiga vê hoje**

1. Conexões nova reúne motor, contas, chave Gemini e permissões (`connections/ConnectionsScreen.kt:94`, `ConnectionsScreen.kt:114`, `ConnectionsScreen.kt:127`).
2. Dispositivos também tem navegação interna de Conexões e conteúdo próprio (`devices/DevicesTab.kt:153`, `DevicesTab.kt:346`), com linguagem de “cérebros” e seleção de modelo.
3. A lista usa capacidades remotas; indisponibilidade cai no celular local (`DevicesTab.kt:125`). Isso não constitui catálogo local completo dos aparelhos conhecidos.

**Onde trava ou confunde:** uma tela cuida da infraestrutura do assistente, outra de aparelhos controláveis, mas ambas expõem contas/motor. Conexões anuncia chaves em Detalhes e mostra cartão de chave separado. Lista sem resposta pode parecer que aparelhos desapareceram. Instalação detectada não confirma login (`RealEngineGateway.kt:136`); para alguns provedores, autenticação feita fora do motor pode não ser reconhecida (`auth.py:135`): mostrar “Não consegui confirmar a entrada”, não afirmar desconexão.

**Como deve ser**

1. **Conexões = preparar o assistente.** Três grupos: “Assistente neste celular”, “Conta do Claude”, “Permissões”. Estados distintos “Não instalado”, “Parado”, “Sem vínculo”, “Precisa entrar na conta”, “Pronto”. Principal contextual **“Preparar motor”**, **“Iniciar o motor”**, **“Conectar Termux”** ou **“Entrar no Claude”**. Melhorar pedidos é opcional e recolhido.
2. Login: “Entre na sua conta na página que será aberta.” / **“Abrir página de entrada”**. Retorno: “Verificando entrada…”; expiração: “A entrada expirou.” / **“Entrar novamente”**. Conta só aparece pronta após verificação real.
3. **Dispositivos = aparelhos que posso usar.** Lista local com “Última conexão: {data}”, sem transformar estado antigo em “Conectado”. Vazio: “Nenhum aparelho adicionado.” / **“Adicionar aparelho”**. Detalhe: nome, conexão atual e ações disponíveis; rede/porta/ADB em **“Detalhes”**.
4. Indisponível: “Não consegui verificar este aparelho agora.” / **“Verificar conexão”**; sem motor oferece **“Iniciar o motor”**. Adicionar mostra passos verificáveis e botão **“Testar conexão”**; não prometer controle antes de pareamento real. Todas as entradas “Abrir Conexões” chegam à mesma tela.

**Critério de aceitação:** entrar por Início, Ajustes e Dispositivos abre a mesma Conexões em Robolectric. Desligar motor preserva catálogo local e marca estado desconhecido; timeout encerra verificação com ação. Login cancelado/expirado não marca conectado nem reinstala programa; negar permissão mantém navegação utilizável.

**Mudanças no motor:** C3 separa estados e ações; reaproveitar rotas de auth existentes com normalização de estado no adaptador. Não inventar descoberta de aparelhos; persistir localmente os já conhecidos e consultar capacidades existentes.

## 7. Consentimento antes de baixar ou instalar qualquer coisa

**O que uma pessoa leiga vê hoje**

1. Retomar/preparar pode entrar em instalação do motor (`TermuxFlow.kt:180`); o comando de instalação inclui `pkg install -y python` (`integration/TermuxBridge.kt:52`).
2. Instalação de CLI pode apenas emitir aviso de tamanho e prosseguir, incluindo dependência alternativa (`cli_setup.py:64`, `cli_setup.py:110`).
3. Reforma pode iniciar ferramentas após escopo (`reform/ReformRoutes.kt:94`, `reform/ReformController.kt:224`); `rea_chain.py:218` estima/avisa e em seguida executa. Wi-Fi não é consentimento.
4. Autonomia total permite CLI sem confirmação (`chat.py:268`, `adapters.py:249`). Uma caixa de diálogo só no instalador não cobre instalações pedidas ao Claude por comandos.

**Onde trava ou confunde:** “Iniciar”, “Conectar” ou “Começar reforma” pode instalar outra coisa; aviso não é autorização. Dependências transitivas, atualização e tentativa alternativa também contam. Hoje não há uma barreira única que garanta a regra.

**Como deve ser**

1. Detectar falta → apenas preparar manifesto: nomes, finalidade, origem, versões, bytes de download e espaço necessário. Nenhum download de pacote, script de instalação ou mudança no ambiente antes da escolha. Consulta de metadados é separada e identificada.
2. Diálogo: **“Instalar programas necessários?”**; “Para {finalidade}, vou baixar {lista}. Download: {tamanho}. Espaço necessário: {espaço}. Conexão: {Wi-Fi/dados móveis}.” Botões obrigatórios **“Instalar”** / **“Agora não”**. Em **“Detalhes”**: versões, fontes e verificação dos arquivos.
3. Tamanho desconhecido: “Ainda não consegui calcular o tamanho. Nada será instalado.” / **“Calcular tamanho”** e **“Agora não”**; não liberar instalação às cegas. Estimativa precisa de limite superior efetivo; tamanho/manifesto maior exige nova escolha antes de baixar a diferença.
4. Instalação consentida: “Instalando {programa} · {tempo}”, bytes reais quando disponíveis. Falha: “A instalação parou em {programa}. Seu trabalho foi guardado.” / **“Revisar instalação”**. Recusa: “Nada foi instalado. Você pode continuar usando o app.” / **“Voltar ao pedido”**. Cancelamento mantém o que já foi instalado e explica isso.
5. Motor existente inicia sem instalar; atualização tem consentimento separado. Sessão de CLI, login, reconexão, autonomia total e rede Wi-Fi nunca dão consentimento implícito. Programas já presentes não geram download.

**Critério de aceitação:** spies de subprocesso/rede provam zero instalação/download antes de “Instalar”, após recusa, consentimento vencido ou manifesto alterado. Toque duplo/retry executa um plano uma vez; dependência alternativa exige novo plano. Testar motor ausente, atualização, CLI, Reforma e pedido de instalação via chat, inclusive autonomia total. Tamanho desconhecido mantém instalação bloqueada e app utilizável.

**Mudanças no motor:** C4 com plano imutável e decisão explícita. Aplicar no bootstrap local, `cli_setup`, `rea_chain` e execução de ferramentas da CLI; negar comandos sem controle equivalente. Apenas instruir o modelo a pedir licença não garante nada: até existir mediação testada, desabilitar caminhos irrestritos capazes de instalar. Sem solução verificável para comandos indiretos/scripts, registrar bloqueio de entrega, não alegar garantia completa.

## 8. Projetos / Criar aplicativo

**O que uma pessoa leiga vê hoje**

1. Projetos abre Ideia → Resumo → Detalhe (`projects/ProjectsHost.kt:35`, `ProjectsHost.kt:163`). “Organizar minha ideia” usa regras locais; a pergunta depende de contagem de palavras (`projects/IdeaOrganizer.kt:18`).
2. Nome deriva de palavras; objetivo é truncado em 90 caracteres; plataforma e critérios são fixos (`IdeaOrganizer.kt:22`). Não é compreensão pelo Claude.
3. Criar salva projeto local e submete somente “Criar repositório privado … no GitHub” (`ProjectsHost.kt:44`, `ProjectsHost.kt:171`). Pedidos posteriores passam por route/submit (`ProjectsHost.kt:142`).
4. Último job concluído torna fase “Pronto” (`projects/ProjectModels.kt:63`). Artefatos vêm de lista inicial vazia em produção (`ProjectsHost.kt:48`).

**Onde trava ou confunde:** promessa de aplicativo vira criação de repositório; terminar essa tarefa não prova aplicativo pronto. Ideia longa perde informação no objetivo. GitHub vira obstáculo cedo demais. Fluxo de chat e fluxo de projeto têm semânticas diferentes; não há vínculo explícito com conversa do projeto.

**Como deve ser**

1. Principal **“Criar aplicativo”** → “O que o aplicativo precisa fazer por você?”; exemplos só preenchem campo. Voz segue fluxo 5. Guardar ideia completa e rascunho local desde a primeira edição.
2. **“Salvar ideia e conversar”** cria projeto local com estado “Ideia guardada” e abre conversa própria, levando texto integral diretamente ao Claude. Sem motor: “Sua ideia está guardada. Inicie o assistente para continuar.” / **“Iniciar o motor”**.
3. Claude esclarece o que falta, sem formulário obrigatório de stack/repositório. Pessoa vê resumo em linguagem comum e **“Começar aplicativo”**; isso é a continuação da conversa, não uma criação automática de GitHub. Contas/ferramentas só entram quando necessárias, sempre com consentimento para instalar.
4. Detalhe mostra “Próximo passo: {ação confirmada}” / **“Continuar projeto”**, conversa vinculada e entregas verificadas. “Repositório criado” é marco, não “Aplicativo pronto”. Sem arquivos: “Ainda não há arquivos prontos para abrir.” / **“Continuar projeto”**. Demora usa C1; erro mantém projeto e oferece **“Tentar de novo”**. Tecnologia, branch e hashes em **“Detalhes”**.

**Critério de aceitação:** criar offline, reiniciar e retomar preserva ideia integral e ID; dois toques não duplicam projeto/envio. Atalho Início e Projetos convergem, enviando diretamente ao Claude sem classificador ou GitHub obrigatório. Job de repositório concluído não apresenta aplicativo pronto; entrega sem evidência não libera APK. Sair/voltar mantém sessão do projeto, sem misturar conversa geral.

**Mudanças no motor:** C1 acrescenta `project_id` opcional; projeto/rascunho continuam locais. C5 expõe entregas e evidência; não criar CRUD remoto de projetos agora. Repositório passa a ação posterior explícita.

## 9. Reformar apps

**O que uma pessoa leiga vê hoje**

1. Escolher APK → Escopo → Reversão → Projeto → Fidelidade, com ferramentas como apoio (`Routes.kt:153`). Confirmar escopo já solicita continuação (`ReformRoutes.kt:94`).
2. Ferramentas sem reforma e instalação ao lado do original viram pedido de chat (`ReformRoutes.kt:167`, `ReformRoutes.kt:149`); “Testar no aparelho” relê estado, não executa teste novo (`ReformRoutes.kt:148`).
3. O motor dispõe de passos de ferramentas e verificação (`reform_http.py:148`); instalar ferramentas pode acontecer antes de explicar tamanho/consentimento.

**Onde trava ou confunde:** clicar em teste não testa; escopo não é consentimento de instalação. Medidor sem teste não demonstra fidelidade. Continuação só escolhe passos automáticos conhecidos (`ReformController.kt:215`); após cobertura não executa reconstrução. Pausar cancela a espera local sem confirmação de parada remota (`ReformController.kt:235`). Há callback vazio de artefato, hoje alimentado por lista vazia (`ReformRoutes.kt:129`). Não prometer reforma completa com esse caminho.

**Como deve ser:** **“Escolher aplicativo”** → seletor APK → “O que você quer mudar?” → **“Revisar mudanças”**. Mostrar “Vamos preservar {escopo} e mudar {objetivo}”; principal **“Começar reforma”**. Ferramentas faltantes abrem C4 e recusar mantém escopo. “Entendendo o aplicativo · {tempo}” explica Reversão; métricas só com medição. Sem APK: “Escolha o arquivo do aplicativo que quer reformar.” / **“Escolher APK”**. Erro: “Não consegui ler este arquivo.” / **“Escolher outro APK”**. Sem teste: “Ainda não foi testado neste aparelho.” / **“Ver como testar”**; não usar “Testar” para reler. IDs, comandos e análise técnica em **“Detalhes”**. Instalação de APK também exige escolha explícita e só se libera após qualidade comprovada.

Ao atingir o limite atual: “Análise concluída. A reconstrução ainda não está disponível neste fluxo.” / **“Continuar no chat”**, levando escopo sem prometer APK. Se só impedir novas etapas, botão **“Pausar próximas etapas”**, com “A etapa já iniciada pode continuar no motor”; só declarar parada com confirmação.

**Critério de aceitação:** selecionar APK/compartilhar, cancelar, voltar e retomar mantém escopo sem instalar. Sem motor, todas as etapas acessíveis exibem último estado ou instrução com ação. Teste não executado nunca gera fidelidade medida nem “aprovado”; gateway falso distingue consultar evidência de executar verificação. Confirmar escopo não instala antes de C4; após cobertura e ao pausar, texto explicita os limites sem toque mudo ou falsa parada.

**Mudanças no motor:** reutilizar reforma existente, acoplar C4 antes de `tools`; C5 exige evidência real para entrega. Não inventar endpoint de teste físico até existir executor: corrigir o texto/ação agora e manter `verify` restrito ao que realmente verifica.

## 10. Ajustes

**O que uma pessoa leiga vê hoje:** navegação abre Ajustes e auxiliares (`Routes.kt:182`). Há permissões por tipo/ferramenta, “Permitir tudo (inclui root)” e gastos (`settings/SettingsSections.kt:64`, `SettingsSections.kt:96`). Estado lê/salva política/modelos no motor (`settings/SettingsState.kt:70`, `SettingsState.kt:152`). Algumas rotas declaradas não têm tela (`Routes.kt:186`).

**Onde trava ou confunde:** root, modelos e política chegam antes de preferências simples. “Faz sem perguntar” contradiz consentimento obrigatório. Rotas declaradas não comprovam funcionalidade; configuração remota não deve se apresentar como preferência local já aplicada.

**Como deve ser:** mostrar preferências locais, **“Abrir Conexões”**, **“Ver sessões”** e **“Ver diagnóstico”**; opções técnicas sob **“Detalhes”**. Aviso fixo: “Instalações sempre pedem sua confirmação.” Principal **“Salvar ajustes”** quando há edição; sucesso local “Preferências salvas neste celular.” Remoto sem motor: “Estas alterações ainda não foram aplicadas ao assistente.” / **“Iniciar o motor”**. Demora >10 s oferece **“Tentar salvar”** preservando edição; não sobrescrever escolhas com atualização atrasada. Sem dados de diagnóstico: “Ainda não há uma verificação.” / **“Verificar assistente”**.

**Critério de aceitação:** Robolectric abre Ajustes offline e salva preferências locais após recriação. Falha de persistência remota não apresenta sucesso nem apaga edição. Nenhuma opção de autonomia dispensa C4; todos os links visíveis têm destino real e ação testada.

**Mudanças no motor:** manter contratos atuais de política, sem criar outro sistema de configuração. A barreira C4 independe dessas políticas; C3 fornece diagnóstico resumido. Recursos sem adaptador não ganham botão ativo até existir caminho completo.

## 11. Atividade

**O que uma pessoa leiga vê hoje:** lista jobs e mantém até 300 eventos em memória (`activity/ActivityTab.kt:80`, `ActivityTab.kt:100`); aprova, recusa, cancela e reenvia (`ActivityTab.kt:135`). O gateway offline preserva jobs e fila (`offline/OfflineFirstGateway.kt:77`). Reabrir no chat mostra resumo do job, não necessariamente conversa original (`ChatController.kt:398`).

**Onde trava ou confunde:** eventos locais não sobrevivem à recriação; retry reconstrói só tipo+título com nova chave (`ActivityTab.kt:139`), arriscando perder argumentos/repetir efeito. Trabalhos de chat e jobs não são uma história única. Não afirmar que toda lista desaparece offline: há cache.

**Como deve ser:** grupos “Em andamento”, “Precisa de você”, “Concluídos”; cada item diz o que ocorreu e próximo passo. **“Abrir conversa”** só quando houver vínculo; caso contrário **“Ver trabalho”**. Sem registros: “Nenhum trabalho por aqui ainda.” / **“Escrever pedido”**. Offline: “Mostrando atividade guardada neste celular.” / **“Atualizar atividade”**. Falha de ação: “Não confirmei esta ação. Vou verificar o estado antes de tentar novamente.” / **“Verificar trabalho”**. Tempo/fase usa C1; logs brutos somente sanitizados em **“Detalhes”**.

**Critério de aceitação:** recriação/offline preserva lista e último estado conhecido sem fingir atualização. Retry usa pedido completo e identidade da tentativa, nunca apenas título; dois toques não repetem efeito. Abrir item com sessão chega à conversa correta; item sem sessão abre detalhe local e não contamina chat atual.

**Mudanças no motor:** C5 define retomada/retry com vínculo; conservar corpo original no servidor. Cancelamento/timeout é estado verificável, não prova de que nada executou.

## 12. “Mais inteligência” sem outra IA na frente

**Hoje:** há sugestões estáticas, “Continue.” e ações de erro (`ChatScreen.kt:129`, `ChatScreen.kt:142`); motor já traduz nomes de ferramentas em frases (`chat.py:38`). Não há base para dizer que ele conhece o progresso real além desses eventos.

**Problema:** adivinhar próximo passo, sucesso ou tempo restante piora a confiança. **Como deve ser:** escolher orientação local por estado: sem vínculo → “Conecte o assistente para enviar este pedido.” / **“Abrir Conexões”**; instalação pendente → “Falta sua decisão para continuar.” / **“Revisar instalação”**; projeto parado → “Sua ideia está guardada.” / **“Continuar projeto”**; conclusão → texto do Claude e ação disponível comprovada. Vazio/erro/demora seguem os fluxos anteriores; detalhes técnicos ficam em **“Detalhes”**. Não abrir chat, instalar ou enviar sugestão sozinho.

**Critério de aceitação:** tabela de estados testada em JVM apresenta uma ação principal alcançável por estado, inclusive desconhecido. Sugerir não dispara HTTP/modelo; tocar executa ação explícita ou preenche campo para confirmação. Nenhum texto afirma resultado sem dado/evidência correspondente.

**Mudanças no motor:** apenas C1/C3/C5 fornecem fatos. Não criar planejador, classificador, ranking de modelos ou endpoint “inteligência”.

## Contratos propostos — JSON exato para implementação

Os exemplos abaixo são **novos campos/rotas propostos**, não respostas disponíveis hoje. IDs são exemplos; bytes são ilustrativos, nunca valores fixos de UI. Manter campos existentes; adicionar campos de forma compatível. HTTP autenticado em loopback; nenhuma resposta contém segredo, prompt de sistema ou comando bruto. Datas novas em ISO 8601 UTC; `created_at` de mensagens mantém segundos Unix por compatibilidade. Durações em ms, bytes inteiros ≥0; `null` significa desconhecido.

**Regra comum:** novas mutações com efeito persistente recebem `request_id` UUID persistido antes do envio. Repetição com mesmo corpo devolve resultado original; corpo diferente → 409 `request_conflict`. Registrar atomicamente antes do efeito; estado incerto exige consulta, nunca replay automático após reinício. IDs de evento monotônicos e duráveis; terminal nunca volta a executando. Erro proposto: HTTP 409 (código/status variam conforme causa):

```json
{"error":{"code":"request_conflict","message_pt":"Este pedido já foi usado com outro conteúdo.","action":"review_request","retryable":false}}
```

### C1 — envio, andamento e parada (P1)

`POST /v1/chat`, HTTP 202; `session_id:null` cria conversa, ID existente retoma sem trocar silenciosamente. `project_id` opcional vincula projeto local; texto comum força executor Claude no servidor.

```json
{"request_id":"11111111-1111-4111-8111-111111111111","text":"Quero criar uma lista de compras","session_id":null,"workspace":"default","project_id":null}
```
```json
{"chat_id":"22222222-2222-4222-8222-222222222222","session_id":"33333333-3333-4333-8333-333333333333","request_id":"11111111-1111-4111-8111-111111111111","state":"running"}
```

Evento `chat.status` imediato, a cada 5 s e mudança de fase. Envelope igual ao barramento existente (`type`/`payload`, cursor no transporte); adicionar `seq` para descartar repetição/atraso por execução:

```json
{"type":"chat.status","payload":{"chat_id":"22222222-2222-4222-8222-222222222222","session_id":"33333333-3333-4333-8333-333333333333","seq":7,"state":"running","phase":"reading_files","elapsed_ms":30000,"last_output_at":"2026-10-28T12:00:20Z","heartbeat_at":"2026-10-28T12:00:30Z","can_stop":true}}
```

`state`: `queued|running|awaiting_consent|stopping|done|error|interrupted`; `phase`: `starting|waiting_claude|reading_files|editing_files|running_tool|responding|unknown`. Fase é último sinal observado, não leitura de pensamento. `GET /v1/chat/{chat_id}` retorna campos do payload mais `text`, `request_id`, `error` (null ou erro comum); suporta reconexão sem reenviar. `GET /v1/chat/requests/{request_id}` devolve esse mesmo snapshot; 404 `request_not_found` se nunca aceito. Adicionar `session_not_found`/`resume_failed` específicos; não mapear todo 4xx para nova sessão.

```json
{"chat_id":"22222222-2222-4222-8222-222222222222","session_id":"33333333-3333-4333-8333-333333333333","request_id":"11111111-1111-4111-8111-111111111111","seq":7,"state":"running","phase":"reading_files","elapsed_ms":30000,"last_output_at":"2026-10-28T12:00:20Z","heartbeat_at":"2026-10-28T12:00:30Z","can_stop":true,"text":"Vou conferir os arquivos.","error":null}
```

`POST /v1/chat/{chat_id}/stop` recebe `{"request_id":"44444444-4444-4444-8444-444444444444"}` e retorna 202 `{"chat_id":"22222222-2222-4222-8222-222222222222","state":"stopping","stopped":false}`. Só emitir terminal `interrupted`/`stopped:true` após confirmar processo encerrado; corrida com conclusão preserva `done`. Terminal atual já existente pode retornar 200 com seu estado. Garantir encerramento do processo após prazo limitado, sem perder parcial.

### C2 — sessões e páginas de mensagens (P1)

`GET /v1/sessions?limit=50&cursor=<opaco>` mantém `sessions` e acrescenta `next_cursor`; item mantém campos antigos e acrescenta `canonical_id`, `history_complete`, `active_chat_id`. `GET /v1/sessions/{id}/messages?limit=100&before=<opaco>` retorna a página mais recente quando não há cursor:

```json
{"session_id":"33333333-3333-4333-8333-333333333333","messages":[{"id":"claude:message-uuid:0","role":"user","text":"Minha ideia","created_at":1793188800}],"previous_cursor":"page-older-1","has_more":true,"history_complete":true,"source":"terminal","revision":"r17","active_chat_id":null}
```

IDs estáveis por origem/registro/bloco, não posição do recorte; cursor opaco valida sessão e revisão. `history_complete` indica integridade da fonte, não se todas as páginas foram carregadas; `has_more` indica paginação. Arquivo parcialmente legível retorna `history_complete:false` e `warning_code:"source_partial"`; não ocultar corte. Atualização concorrente não duplica IDs; revisão incompatível retorna 409 `history_changed` e cliente atualiza preservando cache. Resolver alias `cli_session` para mesma conversa canônica, sem duplicar linha na lista; preservar origem terminal mesmo após continuar no AURA. Nunca escrever nos arquivos do Claude.

### C3 — estado do assistente (P2)

`GET /v1/setup/status`, HTTP 200 quando motor responde; ausência de resposta é estado local, não esse JSON fictício:

```json
{"engine":{"state":"ready","update_available":false},"claude":{"installation":"missing","auth":"unknown"},"next_action":"review_installation","checked_at":"2026-10-28T12:00:00Z"}
```

`engine.state`: `ready|update_required`; instalação: `missing|installed|unsupported`; auth: `unknown|signed_out|pending|signed_in|expired`. `next_action`: `none|review_installation|sign_in|check_again`. Diagnóstico é somente leitura e não instala, atualiza ou inicia login. Estados locais adicionais: `termux_missing|permission_missing|unpaired|engine_stopped|unreachable`; não confundir `unreachable` com parado confirmado.

### C4 — instalação com decisão vinculada ao plano (P2)

`POST /v1/install-plans` recebe `{"request_id":"55555555-5555-4555-8555-555555555555","target":"claude","reason":"Responder aos seus pedidos"}`. `target`: `engine|claude|reform_tools|artifact`; alvo exato de artefato/reforma acrescenta `resource_id`. Resposta 201:

```json
{"plan_id":"plan-1","manifest_hash":"sha256:manifest-example","state":"awaiting_consent","items":[{"name":"Claude Code","version":"resolved-version","source":"registry.npmjs.org","download_bytes":12000000,"installed_bytes":40000000,"checksum":"sha256:package-example"}],"download_bytes":12000000,"installed_bytes":40000000,"size_kind":"exact","expires_at":"2026-10-28T12:10:00Z","network":"wifi"}
```

`size_kind`: `exact|upper_bound|unknown`; `unknown` usa bytes null e `state:"size_unknown"`, sem aceitar confirmação. Manifesto inclui todas as dependências/scripts autorizados; fontes/versões resolvidas e hashes reais. Antes do motor existir, manifesto empacotado/consultado pela ponte local segue o mesmo formato e validade; registrar consentimento local antes de qualquer comando.

`POST /v1/install-plans/{plan_id}/decision` recebe `{"request_id":"66666666-6666-4666-8666-666666666666","manifest_hash":"sha256:manifest-example","decision":"install"}` ou `decision:"not_now"`. Aceite → 202 `{"plan_id":"plan-1","state":"installing"}`; recusa → 200 `{"plan_id":"plan-1","state":"declined"}`. Hash alterado, plano vencido, tamanho desconhecido → 409 (`plan_changed|plan_expired|size_unknown`) sem execução. Sem aceite → 403 `consent_required`. Autorização não vale para outro plano.

`GET /v1/install-plans/{plan_id}` devolve manifesto mais `progress`; evento equivalente `install.status` usa payload `{"plan_id":"plan-1","state":"installing","item":"Claude Code","downloaded_bytes":6000000,"elapsed_ms":5000,"error":null}`. Estados finais `completed|failed|cancelled`; `POST /v1/install-plans/{plan_id}/cancel` recebe `request_id`, retorna 202 `{"plan_id":"plan-1","state":"cancelling"}` e aguarda final. Retomada só reaproveita aceite ainda válido para o mesmo manifesto, sem baixar dependência extra.

### C5 — entregas e trabalhos (P3; Reforma consome)

`GET /v1/projects/{project_id}/artifacts` é consulta de saídas vinculadas, não cadastro remoto de projetos. HTTP 200:

```json
{"artifacts":[{"id":"artifact-1","project_id":"project-1","name":"Aplicativo.apk","kind":"apk","size_bytes":12000000,"sha256":"file-hash","quality":"unverified","evidence":[],"can_install":false}]}
```

`quality`: `unverified|failed|passed`; evidência válida tem formato `{"kind":"test_report","reference":"report-1","result":"passed","created_at":"2026-10-28T12:00:00Z"}`. `can_install` só true quando os critérios aplicáveis da matriz estiverem comprovados; ainda exige C4 e instalação Android explícita. Ausência de artefato retorna lista vazia; nunca fabricar APK ou percentual de fidelidade.

`POST /v1/jobs/{id}/retry` recebe `{"request_id":"77777777-7777-4777-8777-777777777777"}`; servidor recupera pedido integral, verifica segurança/estado anterior e retorna 202 `{"job_id":"job-2","retry_of":"job-1","state":"queued","session_id":null,"project_id":"project-1"}`. Já ativo/incerto → 409 `job_not_retryable`, sem duplicar efeito. Repetição da mesma chave retorna `job-2`; autorização de instalação não migra para plano novo. Acrescentar `session_id`/`project_id` opcionais aos jobs existentes; sem vínculo, abrir detalhe local.

### C6 — melhoria opcional, sem intérprete (P1)

Manter `POST /v1/prompt/improve`: `{"text":"Minha ideia completa","style":"equilibrado"}` → 200 `{"improved":"Pedido reescrito"}`. Executar somente por toque, com Gemini CLI já instalado/autenticado e sem API paga; não usar fallback nem instalar. Falta de programa/conta → 409 com erro comum `optional_provider_unavailable`, ação `open_connections`. Prazo 30 s; original intacto. Gemini nunca participa de C1 ou classificação. Nenhuma chave precisa ser colada pelo dono.

## Pacotes de trabalho — três frentes sem arquivos concorrentes

**Antes do paralelo:** coordenador fixa contratos acima, cria portas testáveis e reserva a ligação final. `A` abaixo = `android/app/src/main/java/ai/aura/app/`; `T` = `android/app/src/test/java/ai/aura/app/`; `E` = `engine/aura_engine/`. Listas com diretório significam todos os arquivos sob ele. Arquivos novos estão marcados “novo”. Não editar arquivos de outro pacote para fazê-lo compilar: usar portas/fakes acordados e encaminhar necessidade ao coordenador.

**Arquivos comuns, somente coordenador, em etapa serial antes/depois:** `A/MainActivity.kt`, `A/ui/AuraApp.kt`, `A/ui/nav/Routes.kt`, `A/ui/engine/UiEngine.kt`, `A/integration/EngineApi.kt`, `A/integration/AuraEngineClient.kt`, `A/integration/RealEngineGateway.kt`, `A/offline/OfflineFirstGateway.kt`, `A/offline/LocalStore.kt`, `E/server.py`, `E/core.py`, `E/store.py`. Coordenador extrai/delega adaptadores para os arquivos novos abaixo e liga callbacks; mantém autenticação antes dos handlers. Testes globais de navegação/offline e documentação geral também são dele. Essa etapa é necessária: os três domínios hoje compartilham esses arquivos.

### P1 — Chat + Voz + Sessões; motor de conversa

- **Arquivos exclusivos:** `A/ui/screens/chat/`, `A/ui/screens/sessions/`, `A/ui/screens/voice/`, `A/voice/`, `A/ui/components/Composer.kt`, `A/integration/ChatFailures.kt`; novos `A/integration/ChatSessionGateway.kt`, `A/ui/screens/chat/ConversationStore.kt`, `A/ui/components/InlineVoiceInput.kt`; `E/chat.py`, `E/sessions.py`, `E/sessions_http.py`, `E/routing.py`; novo `E/chat_http.py`.
- **Ordem:** (1) RED A→B→A/cache/resume e envio duplicado; (2) cache por conversa + IDs/páginas/alias, sem reset silencioso; (3) C1 e continuidade após recriação, sem fallback de IA; (4) microfone inline reutilizável e retirada de triagem/autoenvio; (5) C6 e ações contextuais. Claude deve consumir a barreira de instalação de P2; não implementá-la novamente.
- **Testes exclusivos:** `T/ui/screens/chat/`, `T/ui/screens/sessions/`, `T/ui/screens/voice/`, `T/voice/`, `T/integration/RealEngineChatTest.kt`, `T/integration/RealEngineSessionsTest.kt`, `T/integration/ChatFailuresTest.kt`, `T/e2e/ChatScreenE2ETest.kt`; `engine/tests/test_chat.py`, `engine/tests/test_sessions.py`. Acrescentar testes para relógio/heartbeat, stop, crash/retry, >8 MiB, paginação, atraso entre sessões, partial/final de voz e zero classificação/API paga.

### P2 — Conexões + Dispositivos + Onboarding + consentimento; inclui Reforma/Ajustes

- **Arquivos exclusivos:** `A/ui/screens/connections/`, `A/ui/screens/devices/`, `A/ui/screens/DevicesScreen.kt`, `A/ui/screens/onboarding/`, `A/ui/screens/permissions/`, `A/ui/screens/settings/`, `A/ui/screens/checkup/`, `A/permissions/`, `A/checks/`, `A/ui/screens/reform/`, `A/offline/TermuxPort.kt`; `A/integration/TermuxBridge.kt`, `RealTermuxPort.kt`, `TermuxResultParser.kt`, `EngineCredentialStore.kt`, `SettingsGateway.kt`, `ReformGateway.kt`, `NetworkReporter.kt`, `SharedApkCopier.kt` (estes últimos no mesmo `integration/`); novos `A/integration/SetupGateway.kt`, `A/ui/screens/connections/InstallConsent.kt`; `E/cli_setup.py`, `E/auth.py`, `E/adapters.py`, `E/rea_chain.py`, `E/reform_http.py`, `E/reform_ledger.py`; novos `E/install_consent.py`, `E/setup_http.py`.
- **Ordem:** (1) RED zero download sem aceite em bootstrap/CLI/reforma/chat; (2) C4 e manifesto/tamanho/validade, removendo reinstalação automática; (3) C3 e login sem instalação implícita; (4) Conexões única, catálogo local e onboarding adiável; (5) Ajustes sem bypass, Reforma sem falso botão de teste. Reforma fica inteira aqui para não dividir os arquivos de consentimento com P3.
- **Testes exclusivos:** `T/ui/screens/onboarding/`, `T/ui/screens/connections/`, `T/ui/screens/settings/`, `T/ui/screens/reform/`, `T/permissions/`, `T/checks/`, `T/integration/ReformGatewayTest.kt`, `T/integration/SettingsGatewayTest.kt`, `T/integration/NetworkReporterTest.kt`; novos `T/ui/screens/devices/DevicesPersistenceTest.kt`, `engine/tests/test_install_consent.py`; existentes `engine/tests/test_primeiro_uso.py`, `test_rea_chain.py`, `test_reform_http.py`, `test_reform_ledger.py`. Testar recusa, expiração, alteração de tamanho, reinício e comandos indiretos; sem cobertura da barreira, entrega bloqueada.

### P3 — Projetos + Criar aplicativo + Início + Atividade

- **Arquivos exclusivos:** `A/ui/screens/projects/`, `A/ui/screens/ProjectsScreen.kt`, `A/ui/screens/HomeScreen.kt`, `A/ui/screens/home/`, `A/ui/screens/activity/`, `A/ui/screens/ActivityScreen.kt`; novos `A/integration/ProjectActivityGateway.kt`, `A/ui/screens/projects/ProjectDraftStore.kt`, `A/ui/screens/activity/ActivityHistoryStore.kt`, `E/project_activity_http.py`. Não alterar `ChatController`, `Composer`, Reforma ou a barreira C4.
- **Ordem:** (1) RED criação offline/ideia integral/atalhos iguais; (2) persistir projeto e vínculo de sessão, substituir criação automática de repositório por conversa; (3) Início resiliente e componente de voz de P1 através da porta acordada; (4) C5, atividade persistida e retry completo; (5) entregas só com evidência. Enquanto P1 trabalha, usar fake do callback `openProjectConversation(projectId, text, requestId)`; sessão só é vinculada após confirmação de C1.
- **Testes exclusivos:** novos `T/ui/screens/projects/ProjectCreationFlowTest.kt`, `T/ui/screens/projects/ProjectDeliveryTest.kt`, `T/ui/screens/activity/ActivityRecoveryTest.kt`, `T/ui/screens/home/HomeRecoveryTest.kt`, `engine/tests/test_project_activity.py`. Testar título longo, escrita local falhando/atrasada, retorno de chat, retry com argumentos completos, job concluído sem APK e ausência de GitHub/Termux.

**Prova obrigatória de cada frente:** escrever teste, registrar RED, implementar e registrar GREEN; Compose com Robolectric/portas falsas, motor com subprocessos/rede controlados. Um Gradle por vez: `scripts/gw.sh :app:testDebugUnitTest --tests <classe> --offline`. Motor: `cd engine && python -m unittest`; execução focada por módulo durante desenvolvimento. Coordenador roda regressão de navegação, todos os botões, matriz offline e suíte integrada; push autorizado pelo coordenador deve ficar verde em `android-ci`, `Engine contract CI`, `design-coverage`. Esta análise não executou essas suítes e não libera APK. Hardware real continua necessário para provar qualidade acústica, comportamento do serviço de voz e atualização por cima, sem substituir os testes JVM.

## O que não vale fazer agora (YAGNI)

- Nova IA para interpretar pedido, roteador multi-modelo ou fallback silencioso: mais custo/estado e contraria a decisão.
- Voz em tela própria, escuta contínua, palavra de ativação, envio por silêncio e triagem: primeiro entregar ditado confiável no campo.
- CRUD remoto/sincronização em nuvem de projetos, marketplace de ferramentas, painel de processos como fluxo principal: cache local e contratos mínimos resolvem os problemas atuais.
- Reformulação visual geral, percentuais estimados, promessa de “fidelidade” sem prova e criação automática de GitHub: não corrigem identidade, consentimento ou continuidade.
- Abrir Memória/Capacidades só para preencher navegação, ou remover funcionalidades úteis de sessões externas: ligar apenas caminhos completos e preservar a importação existente.

**Estado deste bloco:** diagnóstico e contratos propostos; implementação pendente. Evidência: referências de código acima. Bloqueios de produto: consentimento em execução irrestrita, histórico truncado/alias e ausência de evidência de teste físico não podem ser disfarçados. Próximo item: coordenador fixa as portas comuns, inicia RED dos três pacotes e registra andamento em `docs/REGISTRO.md`; este arquivo não foi alterado nesta tarefa por restrição de escopo.
