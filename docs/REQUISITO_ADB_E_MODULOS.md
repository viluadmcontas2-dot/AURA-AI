# Requisito do dono: ADB nativo e interface modular (a interface organiza o Termux)

Dono: "inteligência nativa de conexão via ADB: achar o ADB, o celular e a multimídia na rede e deixar conectado já, em vez de pedir ao modelo para ficar rodando e testando"; "poder mandar o Codex instalar o Antigravity e ele instalar"; "a interface só existe para organizar o Termux: flexível, funcional, versátil, modular e em tempo real, funcionando ou não".

## 1. ADB nativo no motor (sem modelo no meio)
O motor, não a IA, descobre e mantém as conexões. Custo zero de tokens.
- **Descoberta:** (a) `adb mdns services` (_adb-tls-connect._tcp e _adb._tcp) quando o adb do Termux suportar; (b) varredura leve da sub-rede local nas portas 5555 (e porta informada) com timeout curto, só dispositivos que respondem ao protocolo ADB; (c) o próprio celular via Depuração sem fio.
- **Pareamento (Android 11+):** a porta e o código de pareamento só o dono vê no celular/central: tela "Parear aparelho" com campos IP:porta e código de 6 dígitos -> `adb pair`. Explicar em PT-BR, passo a passo curto. O app NÃO promete pareamento automático onde o sistema exige código.
- **Catálogo persistido** (aparelhos conhecidos, apelido, último IP, última vez visto, tipo: este celular / outro celular / multimídia / desconhecido).
- **Mantém conectado:** vigia (`adb devices -l` a cada ~10 s e reconexão com backoff ao mudar IP/cair); troca de IP resolvida pelo mDNS/varredura; estado por aparelho: conectado | reconectando | sem resposta | não autorizado (mostra "Autorize no aparelho") | offline.
- **API do motor:** `GET /v1/devices` (catálogo + estado), `POST /v1/devices/scan`, `POST /v1/devices/pair {host, port, code}`, `POST /v1/devices/{id}/connect|disconnect|forget|rename`, `GET /v1/devices/{id}/screenshot`; eventos `device.status` em /v1/events. As ações do chat (`adb` como ferramenta do assistente) passam a usar o catálogo: o assistente recebe a lista de aparelhos conectados como contexto e não precisa procurar.
- **App (Dispositivos):** lista viva com estado e uma ação por aparelho ("Reconectar", "Parear", "Ver tela"), botão "Procurar aparelhos", vazio convidativo ("Nenhum aparelho. Toque em Procurar aparelhos."). Funciona sem motor mostrando o catálogo guardado e "Iniciar o motor".
- **Risco honesto:** mDNS no adb do Termux pode não funcionar (depende da build do android-tools); fallback é a varredura + digitar IP. Central multimídia só aparece se o ADB estiver habilitado nela (muitas exigem modo desenvolvedor). Nada disso foi provado aqui: só o celular do dono prova. Testes do motor usam um `adb` falso.

## 2. Interface modular e em tempo real (limites definidos para não virar infinito)
"Reproduzir tudo do Termux" = três coisas, e só elas:
1. **Atividade ao vivo:** tudo que um assistente executa (comando, saída resumida, arquivo editado) aparece em tempo real num feed, discreto no chat (linhas "Rodando comando…") e completo em Detalhes > "Ver o que foi executado" (comando + saída, com segredos redigidos). Fonte: eventos `chat.tool` enriquecidos com `command` e `output_tail` (já sanitizados pelo motor).
2. **Console de comandos:** tela "Terminal" (em Ajustes > Avançado) para o dono rodar um comando no Termux e ver a saída ao vivo (POST /v1/console/run, saída por eventos), histórico dos últimos comandos, botão Parar. Não é um emulador de terminal (sem TTY interativo): comandos de uma linha/script. Autonomia total do dono; sem travas; mas instalações seguem o plano de consentimento (C4) quando o motor reconhecer o instalador (`pkg`, `npm -g`, `pip`).
3. **Módulos declarados pelo motor:** `GET /v1/modules` -> [{id, title, summary, status: ready|attention|off, actions:[{id, label, needs_consent?}], details}] e `POST /v1/modules/{id}/actions/{action}`. O app renderiza de forma genérica em Conexões ("Ferramentas": ADB, instaladores, Git, etc.). Assim o motor ganha módulos novos sem atualizar o APK. O app tem renderização de reserva para módulo desconhecido ("Ferramenta nova: {título}" + ações).

## 3. "Mandar o Codex instalar o Antigravity"
- O Codex entra como assistente selecionável no chat (junto de Antigravity, Gemini e Claude Code; ver docs/REQUISITO_ESCOLHA_DO_ASSISTENTE.md). Ele executa comandos no Termux em modo de autonomia total.
- O app NÃO instala por trás: quando o motor reconhece um instalador (pkg/npm/pip/curl|sh de instalador oficial) ele cria um plano (C4) e o app mostra "Instalar programas necessários? [Instalar] [Agora não]" ANTES de baixar. Pedir ao Codex "instale o Antigravity" = o assistente propõe, o dono aprova com um toque. Limite honesto: comandos que o assistente encadeia dentro de scripts podem escapar do reconhecimento; o motor registra tudo no feed ao vivo.
- Catálogo de instaladores conhecidos no motor (agy: instalador oficial; claude: npm/glibc fixado; codex; gemini; gh; rclone) com a mesma rota de plano.

## 4. "Funcionando ou não"
- Toda tela abre sem motor; módulos/dispositivos aparecem do cache com "Última verificação: …" e a ação "Iniciar o motor".
- Nenhum módulo trava a tela: timeout visível e "Tentar de novo".

## Critérios de aceitação (motor real com `adb`/CLIs falsos + Compose)
(a) com um `adb` falso que lista 2 aparelhos e muda o IP de um, o catálogo atualiza e a conexão é refeita sem ação do usuário e sem chamar modelo; (b) pareamento usa host/porta/código e mostra erro em PT-BR se o código falhar; (c) `device.status` aparece na tela em até 12 s; (d) feed ao vivo mostra comando e saída sanitizada (sem tokens); (e) console roda um comando e mostra saída em tempo real, Parar mata o processo; (f) módulo declarado pelo motor aparece em Conexões sem alteração no app; módulo desconhecido tem renderização de reserva; (g) pedir instalação reconhecida mostra o plano e NADA é baixado antes de [Instalar]; (h) sem motor, Dispositivos e módulos abrem do cache com ação "Iniciar o motor".

## Ordem / pacote
P5 (depois de P1 e P2 integrados; toca devices/ e adapters.py do P2): 1) motor ADB + catálogo + vigia; 2) Dispositivos vivo no app; 3) feed ao vivo (eventos enriquecidos); 4) módulos genéricos; 5) console. YAGNI agora: emulador de terminal com TTY, múltiplas abas de terminal, edição remota de arquivos.
