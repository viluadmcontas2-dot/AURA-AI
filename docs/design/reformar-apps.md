# Reformar apps da multimídia (REA): decisões e limites

Telas: `telas/Reformar-1..6-*.dc.html`. Eventos: `telas/Contrato.dc.html`.

## O que é
Pegar apps antigos da multimídia, estudar com engenharia reversa e refazer a interface no visual AURA Premium, instalando o app novo AO LADO do original.

## Medidas (palavras fixas na UI)
- Reversão: quanto do app foi entendido (APK inspecionado, telas e fluxos mapeados).
- Reconstrução: quanto da interface nova está pronta.
- Fidelidade: quanto o novo faz igual ao original. Só existe depois de teste real no aparelho; antes mostra "ainda não medida". Nunca inventar número.
- Escopo travado antes de começar: Só visual, Visual + fluxos, Reconstrução completa.

## REA (morluto/rea), conforme a página do projeto
- Instala com `npx rea-agents setup` (Node 22.23.4+, 24.22.1+ ou 26.12.1+). Skill + servidor MCP.
- Para APK precisa de JADX sem tela e JDK completo; apoio: adb, Apktool.
- Separa inspeção estática de captura em execução e devolve evidências, limites e "unknowns".
- A página não menciona Termux. JADX no celular é pesado em memória: verificar no aparelho real. Plano B: dividir o trabalho em partes menores.

## Guardas obrigatórias
1. Confirmação "Sou dono do app ou tenho permissão". Engenharia reversa de app de terceiros pode violar licença; o AURA não decide isso pelo usuário.
2. Apps do sistema e câmera de ré: protegidos, nunca reformados.
3. O app original nunca é apagado. Voltar custa 1 toque.
4. Ferramentas instaladas no Termux com estado e checkpoint por etapa; retomar após o Android encerrar o processo.

## Teste (a automatizar, ainda não feito)
- Fluxos por tela rodados em emulador/aparelho com adb (comparar original x novo). Delegar lotes a CLIs baratas (Codex, agy) quando houver cota; Claude só revisa.
- Nada disso foi executado ainda. Status real: apenas desenho.
