# Registro

Estado inicial: voz, permissões e crash visível feitos; modo offline-first e telas faltantes pendentes.

## T14 Bastidores, Intercorrências, Link-Aberto e Vazios (28/10/2026)
- Objetivo: 4 telas do design sem depender do Termux: Vazios, 11 intercorrências, sheet de link (todo https passa por ela) e Bastidores.
- Estado: código e 8 testes em `T14TelasTest.kt`; a CI do GitHub é a prova (ids no relatório da tarefa). Gradle local ocupado, não rodei.
- Pendente: ilustrações vetoriais dos vazios, barra de passos e opções com seleção das intercorrências, prévia do site e "Abrir no app", visualizador dentro do AURA, abas Logs/Mudanças e tempo de execução nos Bastidores.
- Próximo: ligar `LinkSheetHost` e `BackstageScreen` na navegação (T5).
