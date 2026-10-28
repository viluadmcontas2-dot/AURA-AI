# Revisão de Textos do App AURA AI

## Tabela de Sugestões

| arquivo:linha | texto atual | sugestão | motivo |
| :--- | :--- | :--- | :--- |
| `offline/OfflineFirstGateway.kt:83` | `"Motor desligado"` | `"Motor desligado"` (manter) / contexto: `"Sem resposta do motor"` -> `"Motor não respondeu"` | Frase mais direta e clara em português ativo. |
| `offline/OfflineFirstGateway.kt:97` | `"O motor está desligado. O pedido vai para a fila e o motor interpreta quando voltar."` | `"O motor está desligado. O pedido fica salvo e segue quando ele ligar."` | Reduz carga cognitiva; elimina explicação técnica ("interpreta"). |
| `offline/OfflineFirstGateway.kt:152` | `"O motor não reconheceu o app. Conecte o Termux de novo em Conexões."` | `"O motor não reconheceu o app. Reconecte o Termux na aba Conexões."` | Verbo no imperativo direto e ação clara. |
| `ui/screens/chat/ChatParts.kt:200` | `"Não tenho certeza de que entendi. Toque em Ajustar pedido se estiver errado."` | `"Não entendi bem. Toque em Ajustar pedido para corrigir."` | Mais curto e positivo; troca "se estiver errado" por foco na ação. |
| `ui/screens/chat/ChatParts.kt:228` | `"Só neste celular · o motor ainda não recebeu"` | `"Salvo no celular · o motor ainda não recebeu"` | Padroniza o estado "guardado/salvo" com clareza. |
| `ui/screens/chat/ChatParts.kt:236` | `"Vem depois que o motor receber o pedido"` | `"Disponível quando o motor ligar"` | Mais simples e direto; foca no momento da ação. |
| `ui/screens/chat/ChatParts.kt:314` | `"O motor confirmou a conclusão, mas não enviou detalhes de prova."` | `"O motor terminou a tarefa, mas não enviou o comprovante."` | Troca jargão ("conclusão", "prova") por termos cotidianos. |
| `ui/screens/chat/ChatParts.kt:315` | `"O motor não informou pendências. Confira o resultado no aparelho."` | `"Sem pendências no motor. Confira o resultado no aparelho."` | Frase mais curta e direta. |
| `ui/screens/chat/ChatParts.kt:328` | `"Faça um novo pedido quando quiser."` | `"Envie um novo pedido quando quiser."` | Usa verbo de ação mais específico. |
| `ui/screens/chat/ChatParts.kt:331` | `"Nada foi feito ainda: ${job.title}."` | `"Pedido em espera: ${job.title}."` | Tom mais construtivo e menos frustrante que "nada foi feito". |
| `ui/screens/chat/ChatParts.kt:334` | `"Ligue o motor e o pedido segue sozinho, ou cancele se não quiser mais."` | `"Ligue o motor para o pedido seguir, ou cancele se preferir."` | Reduz tamanho e remove tom negativo ("não quiser mais"). |
| `ui/screens/chat/ChatParts.kt:361` | `"Sem resposta do Termux agora. Continuo tentando; o que aparece abaixo pode estar desatualizado."` | `"Sem resposta do Termux. Tentando de novo; as informações abaixo podem estar desatualizadas."` | Frases mais curtas e diretas. |
| `ui/screens/chat/ChatParts.kt:386` | `"Novo pedido"` | `"Fazer novo pedido"` | Botão com verbo + objeto no imperativo. |
| `ui/screens/chat/ChatParts.kt:390` | `"Novo pedido"` | `"Fazer novo pedido"` | Botão com verbo + objeto no imperativo. |
| `ui/screens/chat/ChatParts.kt:510` | `"Abra o Termux, confirme que o AURA está ativo e toque em Tentar de novo."` | `"Abra o Termux, confirme o AURA ativo e toque em Tentar de novo."` | Mais enxuto para leitura rápida. |
| `ui/screens/chat/ChatModels.kt:99` | `"Este pedido pode criar ou alterar arquivos e serviços conectados."` | `"Este pedido pode criar ou alterar arquivos."` | Reduz sobrecarga de texto desnecessária. |
| `ui/screens/chat/ChatModels.kt:126` | `"O motor recusou ou não conseguiu terminar o pedido."` | `"O motor não conseguiu concluir o pedido."` | Remove ideia punitiva ("recusou") e simplifica. |
| `ui/screens/activity/ActivityModels.kt:74` | `"O pedido está guardado neste celular. O motor está desligado e ainda não recebeu."` | `"Pedido salvo neste celular. O motor está desligado e ainda não o recebeu."` | Frase mais enxuta e direta. |
| `ui/screens/activity/ActivityModels.kt:75` | `"Ele segue sozinho quando o motor ligar. Se não quiser mais, cancele o pedido."` | `"Ele segue sozinho quando o motor ligar, ou cancele se preferir."` | Tom amigável e sem julgamento. |
| `ui/screens/activity/ActivityTab.kt:120` | `"Não consegui concluir. Toque de novo; se repetir, confira o Termux."` | `"Não foi possível concluir. Tente de novo ou confira o Termux."` | Ação imediata e clara sem condicional dupla. |
| `ui/screens/activity/ActivityTab.kt:311` | `"Aguardando o motor ligar"` | `"Aguardando o motor"` | Padroniza com as demais telas (`ChatModels` e `ProjectDetailScreen`). |
| `ui/screens/projects/ArtifactDetailScreen.kt:80` | `"Sem prova ainda"` | `"Sem comprovante ainda"` | Mais humano e menos forense/acusatório que "sem prova". |
| `ui/screens/projects/ProjectDetailScreen.kt:104` | `"Os arquivos aparecem aqui quando o AURA mexer no código. Peça uma tarefa na caixa abaixo."` | `"Arquivos aparecem aqui após alterações de código. Envie um pedido abaixo."` | Frase mais clara e objetiva com verbo + objeto. |
| `ui/screens/projects/ProjectsHost.kt:95` | `"Não consegui falar com o AURA neste aparelho. Confira se o Termux está ligado e tente de novo."` | `"Sem conexão com o AURA. Verifique o Termux e tente de novo."` | Frase curta: diz o que houve e UMA ação direta. |
| `ui/screens/projects/ProjectsHost.kt:171` | `"Não consegui enviar o pedido. Escreva de novo e toque em enviar."` | `"Falha ao enviar o pedido. Tente enviar de novo."` | Não pede para a pessoa reescrever todo o texto (frustrante no TDAH). |

---

## Padronizar

1. **"Aguardando o motor"** (unificar com "Aguardando o motor ligar").
2. **"Pedido salvo"** (unificar com "Pedido guardado").
3. **"Comprovante"** (substituir "prova" / "detalhes de prova").
4. **"Fazer novo pedido"** (botão de ação imperativo em vez de "Novo pedido").
5. **"Sem conexão com o Termux"** (unificar com "Sem resposta do Termux" para quedas de comunicação).
6. **"Tente de novo"** (padronizar ações de repetição de tentativa).

---

## Avaliação do controlador (agente vermelho, 27/10)

Delegação nº 1 ao agy, pelo `delegar.ps1` do dono: texto puro, 10,5 mil caracteres, `gemini-3.8-flash-low`, 49 s, plano gratuito. O agy devolveu 25 sugestões; eu revisei linha a linha antes de qualquer edição.

**Aceitas (3), vão para quem é dono do arquivo (T13/T15):** `ActivityTab.kt:311` "Aguardando o motor" (padroniza); `ChatParts.kt:334` frase mais curta; `ProjectsHost.kt:171` só se o rascunho for mantido (senão "Escreva de novo" está errado: T13 confere).
**Padronizar aceito:** "Aguardando o motor"; "Tentar de novo" como ação de repetir.

**Rejeitadas (maioria), com motivo:**
- Troca de "prova" por "comprovante" (`ChatParts:314`, `ArtifactDetailScreen:80`, padronização 3): "prova" é conceito do produto (COMPROVADO, "Sem prova ainda") e está no design.
- Muda o sentido, o que a regra 7 da tarefa proibia: `ChatParts:315` ("não informou pendências" virou "sem pendências"), `:236`, `:331` (campo "feito" tem de ser honesto), `ChatModels:99` (tira o aviso de risco sobre serviços conectados).
- Perde a voz em primeira pessoa do AURA ("Não consegui…") ou a fidelidade ao design: `ActivityTab:120`, "Novo pedido" → "Fazer novo pedido", `ChatParts:200`.
- Erra o lugar: `OfflineFirstGateway:152` chama Conexões de "aba" (é uma tela de Dispositivos). `:97` troca texto que um teste da T1 confere ("fila").
- "Salvo" no lugar de "guardado": o código já usa "guardado" quase em todo lugar; trocar só gera ruído.

**Lição para os próximos lotes:** o agy sem o texto do design sugere reescritas que quebram fidelidade e sentido. Só vale entregar a ele o texto do design junto, ou usar o resultado como lista de dúvidas, não de correções. Aceitação: 3 de 25.
