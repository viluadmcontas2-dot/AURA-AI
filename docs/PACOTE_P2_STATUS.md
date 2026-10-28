# Pacote P2 — status (Conexões, Dispositivos, Onboarding, consentimento)

Verificação final: `APP: OK (409 testes, 0 falhas)` e `MOTOR: OK (Ran 289 tests)` (verify.sh).

## Feito e verde
1. **Consentimento antes de instalar (prioridade 1).**
   - Motor: `install_consent.py` (plano imutável: manifesto, tamanho exato/limite superior/desconhecido, validade de 10 min, hash, decisão),
     `setup_http.py` (`GET /v1/setup/status`, `/v1/install-plans*`). `adapters.run_process` recusa comando de instalação sem permissão do plano
     aceito (barreira única, inclui `sh -c`, scripts e `curl` de download). `cli_setup` e `rea_chain` só rodam com `plan_id` aceito; sem
     caminho alternativo automático (`needs_new_plan`); reforma devolve `consent_required`. `/v1/tools/install` sem plano = 403.
   - Testes do motor com espiões de `Popen` e rede: zero instalação antes do aceite, após recusa, plano vencido, hash alterado, tamanho
     desconhecido, reinício; toque duplo roda uma vez; autonomia total não dispensa. RED provado contra b5ac9b6 (instalava `npm install` sem pedir).
   - App: `InstallConsentGate` + diálogo "Instalar programas necessários?" ([Instalar] / [Agora não] / [Calcular tamanho]), plano local do motor
     (Python do Termux "até 150 MB", limite superior declarado), `TermuxFlow` não instala mais ao voltar/abrir/reconectar (só propõe a pedido e
     instala dentro do aceite; vencido/alterado/desconhecido não instalam), `SetupGateway`/`InstallFlow` (contas e reforma usam a mesma barreira).
2. **Conexões única**: "Assistente neste celular" / "Conta do Claude" / "Permissões", estados distintos (Sem Termux, Sem permissão, Sem vínculo,
   Não instalado, Parado, Precisa entrar na conta, A entrada expirou, Pronto) e UMA ação principal; "Pronto" só com a conta confirmada pelo motor.
   "Melhorar pedidos" recolhido; outras contas em "Detalhes".
5. **Reformar**: sem botão "Testar" que só relê (agora "Ver como testar" só explica), "Análise concluída. A reconstrução ainda não está
   disponível neste fluxo." + "Continuar no chat", "Pausar próximas etapas" sem declarar parada; instalar ferramentas passa pelo plano (C4) e
   deixou de virar pedido ao chat.

## Não feito (por prazo)
- **Dispositivos (item 2, parte B)**: catálogo local (`DeviceCatalog.kt`, `ConnectionState.Unknown`, `Verify`) foi só iniciado e NÃO está ligado a
  `DevicesTab`; a aba ainda tem o "Conexões" interno antigo. Sem `DevicesPersistenceTest`.
- **Onboarding (item 3)**: "Preparar assistente / Explorar aplicativo", uma etapa por vez e prazo de 10 s com "Continuar depois" não feitos. Só mudou
  o que o consentimento exige (passo "Preparar motor" no Termux; nada instala ao voltar).
- **Ajustes (item 4)**: aviso "Instalações sempre pedem sua confirmação." e reordenação (preferências locais primeiro, técnico em Detalhes) não
  feitos; "Permitir tudo" ainda diz "nada pergunta antes" (a barreira de instalação independe dele, mas o texto precisa mudar).
- Download real medido (`downloaded_bytes`) é sempre `null`; versões de pacotes Termux/npm não são resolvidas (UI diz "a mais recente disponível").
- Não conferido em celular real: Termux, `--disallowedTools` do Claude Code em modo bypass, diálogo na ponte `RUN_COMMAND`.

## Limitação honesta: comandos que o próprio Claude decide rodar
Instalações feitas por comandos que o Claude escolhe dentro de uma conversa NÃO têm mediação possível hoje. O que existe é melhor esforço:
`claude_cli` (jobs) recebe `--disallowedTools` com prefixos de instalação (`install_consent.claude_guard_args()`), mas isso reduz o risco e não
garante nada (scripts, `sh -c` montado pelo modelo, outros gerenciadores). **O chat (`chat.py`, pacote P1) ainda não usa esse argumento**: o
coordenador/P1 deve acrescentar `install_consent.claude_guard_args()` ao `argv` do `claude` do chat. Até haver mediação testada, não prometer
garantia completa.

## Mudanças em arquivos comuns (aditivas)
`server.py` (rota `setup_http`), `core.py` (`engine.installs`), `MainActivity.kt` (gate, `SetupGateway`, controlador da reforma), `AuraApp.kt`
(`InstallConsentHost`). Testes antigos ajustados por regra nova: `TermuxFlowTest` (instalar só após aceite), `AccountsInstallTest`,
`test_motor_real` (fallback glibc só com plano novo), `ReformDescobertaTest`/`ReformProgressTest` (sem "Testar"), Conexões (novos textos).
