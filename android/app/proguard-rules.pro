# Regras de manutenção do release (R8). O padrão do Android já mantém o que o sistema chama
# (componentes do manifesto, enums, construtores de View); o resto vem das regras de cada biblioteca.
#
# O app guarda o último erro em last_crash.txt e mostra na próxima abertura (MainActivity). Por isso o
# release encolhe e otimiza, mas não troca os nomes por letras e mantém os números de linha.
# Limite: o R8 ainda funde e embute classes pequenas (ex.: o EngineCredentialStore vira
# kotlin.text.MatcherMatchResult). Para ler com certeza um last_crash.txt do release, passe-o pelo retrace
# com build/outputs/mapping/release/mapping.txt.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
