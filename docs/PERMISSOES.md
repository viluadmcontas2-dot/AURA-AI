# Permissões do AURA AI

O app declara só 4 permissões. Cada uma está aqui com o motivo, o momento em que o app pede e o que acontece se você negar.
O teste `ManifestTest` compara esta tabela com o `AndroidManifest.xml` e com o manifesto final do APK: permissão nova só entra se for escrita aqui primeiro.

Regra de pedido (Tarefa 7 do plano): o Android não pergunta nada antes da sua primeira ação. Antes de cada pedido do sistema, o app mostra uma folha própria com o que é, por que precisa, e os botões "Permitir" e "Agora não". Negou duas vezes? O app não insiste e leva você às configurações do app.

## Permissões que o app declara

| Permissão | Por que | Quando pede | Se negar |
|---|---|---|---|
| `android.permission.INTERNET` | Falar com o motor do AURA, que roda no seu próprio celular (endereço `127.0.0.1`). O app não conversa com nenhum outro endereço. | Nunca: é uma permissão "normal", o Android concede sozinho na instalação e não mostra pedido. | Não dá para negar. |
| `android.permission.RECORD_AUDIO` | Microfone, para falar com o AURA (telas de Voz). | Só na primeira vez que você toca no microfone, depois da folha do app. | A voz fica desligada e o app diz isso com uma ação ("Abrir configurações"). Digitar continua igual. |
| `android.permission.POST_NOTIFICATIONS` | Avisar quando o AURA terminar, precisar de uma aprovação sua ou estiver trabalhando. Só existe no Android 13 ou mais novo. | Só quando você liga os avisos (Ajustes, Notificações), depois da folha do app. | Nenhum aviso fora do app. Tudo continua na aba Atividade. |
| `com.termux.permission.RUN_COMMAND` | Permissão criada pelo Termux (nível "perigosa"). Deixa o AURA pedir ao Termux que ligue o motor e faça o pareamento. O Termux também precisa de `allow-external-apps=true`. | No passo do Termux da abertura (Onb-2) e ao tocar em "Iniciar o motor". | O app abre e toda tela funciona. O que depende do motor mostra "Iniciar o motor" e explica o que falta. |

## Permissões que o app NÃO pede

Ficaram de fora porque nenhuma tela nem código do app usa. Pedir o que não usa é o que faz o Android e o Play Protect desconfiarem.

| Permissão | Por que saiu |
|---|---|
| `android.permission.MANAGE_EXTERNAL_STORAGE` | "Acesso a todos os arquivos". Nada lê arquivos fora da pasta do app. Era a tela extra que abria na primeira abertura. |
| `android.permission.READ_EXTERNAL_STORAGE`, `android.permission.WRITE_EXTERNAL_STORAGE` | Nada lê nem grava fora da pasta do app. Se um dia o app precisar de um arquivo seu, usa o seletor do sistema, que não exige permissão. |
| `android.permission.READ_MEDIA_IMAGES`, `android.permission.READ_MEDIA_VIDEO`, `android.permission.READ_MEDIA_AUDIO` | Nenhuma tela lê fotos, vídeos ou músicas. Mesmo caminho: seletor do sistema. |
| `android.permission.CAMERA` | Nenhuma tela usa a câmera. |
| `android.permission.NEARBY_WIFI_DEVICES`, `android.permission.ACCESS_FINE_LOCATION`, `android.permission.BLUETOOTH_CONNECT`, `android.permission.BLUETOOTH_SCAN`, `android.permission.BLUETOOTH`, `android.permission.BLUETOOTH_ADMIN` | O app não procura aparelhos pelo rádio do celular. A multimídia do carro e a TV são controladas pelo motor, por ADB. |
| `android.permission.ACCESS_NETWORK_STATE`, `android.permission.ACCESS_WIFI_STATE`, `android.permission.CHANGE_WIFI_STATE` | Nenhum código olha o estado da rede nem mexe no Wi-Fi: o motor fica no próprio aparelho. |
| `android.permission.REQUEST_INSTALL_PACKAGES` | "Instalar APK" no design é instalar na multimídia do carro, e quem faz isso é o motor, por ADB. O app nunca instala nada no próprio celular. |
| `android.permission.FOREGROUND_SERVICE`, `android.permission.WAKE_LOCK` | Hoje não existe serviço no app. Voltam, junto com uma linha na tabela de cima, quando entrar o serviço de avisos (Tarefa 12) e o microfone em segundo plano (Tarefa 16). |

## Outras travas do manifesto

- **Texto puro só no motor local.** HTTP sem cadeado só vale para `127.0.0.1` e `localhost` (`res/xml/network_security_config.xml`). Todo o resto exige HTTPS.
- **Sem backup.** `allowBackup="false"`, com regras que também excluem tudo da nuvem e da cópia entre aparelhos. A credencial do motor fica presa ao cofre de chaves (Keystore) do aparelho e não serviria em outro.
- **Só a tela inicial é aberta por fora.** É o único componente `exported`. O receptor do pareamento é interno. O `ProfileInstallReceiver`, que o AndroidX acrescenta aberto, foi removido do manifesto.
- **Visibilidade de pacotes (`queries`).** Só `com.termux` (saber se o Termux está instalado) e a ação de reconhecimento de voz (saber se o aparelho tem reconhecedor). O app não usa `QUERY_ALL_PACKAGES`.
- **Release.** Encolhido (`isMinifyEnabled` e `isShrinkResources`), não depurável, assinado com a mesma chave do v5 para instalar por cima.
- **Permissão interna do AndroidX.** `ai.aura.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` é criada pelo `androidx.core`, só vale para o próprio app (nível assinatura) e não pede nada a você. Aparece no `aapt2 dump permissions`; o teste a ignora.

## Como conferir no APK

```
aapt2 dump permissions app-release.apk
aapt2 dump badging app-release.apk          # sem "application-debuggable"
aapt2 dump xmltree --file res/mipmap-anydpi-v26/ic_launcher.xml app-release.apk   # adaptive-icon com monochrome
adb shell dumpsys package ai.aura.app       # permissões concedidas e componentes
```

## Como mudar esta lista

1. Escreva a linha na tabela "declara" (permissão, por que, quando pede, se negar).
2. Declare a permissão no `AndroidManifest.xml`.
3. Rode `scripts/gw.sh :app:testDebugUnitTest --tests "ai.aura.app.manifest.*" --offline`. O teste falha se as duas listas (ou o manifesto final do APK) não baterem.
