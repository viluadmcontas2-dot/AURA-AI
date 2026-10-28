# Auditoria final: autonomia total, orquestração barata e persistência

Decisões do dono (valem acima de qualquer regra anterior do repositório):
1. **Autonomia total por padrão.** Sem aprovações, sem proteções, sem recusas. Travas existem só se o dono pedir ("AURA, não escreva nada que possa quebrar o aparelho"); viram regras do usuário na Memória e são editáveis/removíveis.
2. **Claude Code só orquestra, o mínimo possível.** O trabalho pesado vai para o Antigravity (`agy` CLI com o login do dono) ou, quando fizer sentido, para a API do Gemini. Claude Code decide, delega e revisa; não lê código inteiro nem gera texto longo.
3. **Dados móveis: nunca baixar pacotes do Termux.** Só em Wi-Fi/rede não medida. Pacote > 100 MB: **aviso discreto, não bloqueante**, interativo (ver tamanho, ok), em qualquer rede permitida.
4. **Persistência total:** continuar de onde parou depois de travar, cair, o Android matar ou reiniciar.
5. **Objetivo:** o celular vira um ambiente onde o Claude Code faz tudo que faria num PC, com engenharia reversa (REA) no máximo de autonomia.

## Visões cruzadas (o que checar em cada uma)
- **Dono (autonomia):** aprovação por risco deixa de ser padrão. Ajustes: "Autonomia: Total (padrão) · Perguntar antes de apagar · Perguntar tudo". Qualquer trava é escolha dele.
- **Custo e tokens:** roteamento determinístico primeiro (REA, JADX, Apktool não usam modelo); modelo só para nomear, redigir e resumir; cache por sha256; `choose_model`: agy (assinatura) → Gemini API → Claude. Trava de custo é um interruptor dele (padrão: ligada, porque dinheiro não volta; um toque desliga).
- **Rede e dados:** o app informa ao motor o tipo de rede (`POST /v1/network {metered, transport}`); passos que baixam (pkg, curl, npm) ficam em `waiting_wifi` e retomam sozinhos; nunca falham por isso. Tamanho vem de HEAD/`Content-Length` ou `pkg` simulado.
- **REA no ambiente:** Node 22.23.4+, JDK 21, JADX headless MCP (jar com sha256 fixo), Apktool, adb, `rea-agents`. O MCP sobe por stdio; `tools/list` é a verdade; contratos e ledger em `docs/REFORMAR_CONTRATO_LEDGER.md`.
- **agy no Termux:** o modo headless pede permissão de arquivo e trava; o AURA grava a regra de permissão do `agy` para a pasta de trabalho no onboarding (decisão do dono, no aparelho dele).
- **Persistência:** job com `idempotency_key`; ledger/checkpoint por passo; fila local no app; o motor roda como serviço do Termux com `termux-wake-lock`; Checkup explica o encerramento de processos do Android 12+ e o que fazer.
- **Segurança que não tira autonomia (isolamento, não trava):** a REA analisa APK não confiável. Texto vindo do APK (strings, nomes, comentários) NUNCA vira instrução: entra em prompts dentro de blocos delimitados "dados não confiáveis". Sem isso, um APK malicioso pode mandar o agente, que tem shell, fazer qualquer coisa. Isto não bloqueia nada que o dono peça.
- **Experiência:** nenhum botão mudo; sem Termux o app abre; erro sempre com ação.

## Lacunas conhecidas (viram tarefas)
G1 gate de rede/dados no motor e no app · G2 aviso >100 MB (chip não bloqueante) · G3 autonomia padrão "Total" (motor + Ajustes) · G4 roteamento agy/Gemini/Claude com Claude mínimo · G5 bootstrap da permissão do agy · G6 serviço do Termux resiliente (wake-lock, retomada) · G7 cercamento de dados não confiáveis · G8 ledger HTTP (em andamento).
