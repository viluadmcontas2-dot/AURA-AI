# Assinatura de debug estável
A chave android/app/aura-debug.keystore é fixa para manter o certificado entre builds da CI.
É uma chave pública de DEBUG, versionada intencionalmente; não serve como segredo de produção.
Para atualizar por cima preservando dados, instale um APK com esta mesma assinatura e versionCode maior.
APKs antigos com outra assinatura não aceitam esta atualização; preserve os dados antes de qualquer reinstalação.
SHA256: 8505a1ab2acac4b515ad25d736015c1556e5b59fd2d591b44d981dad88f95e4a
