# Validação da base 0.6

Data: **01/10/2026**. Versão `0.6.0-subject-focus`, código 6. Aparelho de referência: **Galaxy A14 5G**.

A 0.6 acrescenta Pessoas / Objetos ao desfoque IA. Pessoas usam ML Kit automático; Objetos usam MagicTouch v1 local, selecionado por toque. Os estados da lente e do recorte digital ficam separados. Perda do objeto interrompe novas análises até seleção explícita, evitando aceitar outro alvo no ponto antigo.

**Esta versão ainda não foi executada no A14.** Compilação, JVM e WebGL não comprovam inferência Android, fluidez, câmera/OES, encoder ou reprodução real. Não há medição de precisão/latência do MagicTouch no aparelho. Os emuladores tentados na etapa 0.3 não completaram boot neste host; as mesmas tentativas não foram repetidas.

## Resultado final

| Verificação | Resultado |
| --- | --- |
| APK universal, assemble/JUnit/lint | BUILD SUCCESSFUL; **121 testes JVM**, zero falhas/erros; lint zero erros e 32 avisos |
| GLSL e LUT | **54 contratos aprovados**, executados novamente com os assets atuais; pixels sintéticos em WebGL/ANGLE |
| Versão/permissões | Código 6 / `0.6.0-subject-focus`, Android mínimo 29, alvo 34; CAMERA, RECORD_AUDIO e permissão interna de receiver; sem INTERNET/ACCESS_NETWORK_STATE |
| Assinatura | apksigner aprovado, certificado de desenvolvimento igual ao da 0.5 |
| Modelos | ML Kit `16.0.0-beta6`; MagicTouch float32 v1 + Tasks Vision `0.10.14`, versões fixas e modelo embarcado |
| Android/A14 | Pendente; sem teste real da inferência, prévia, MP4, player ou custo térmico |

As fontes Kotlin finais passaram pelo Verify completo após as correções da revisão. A configuração adicional `lumaAbi` restringe somente bibliotecas nativas para gerar um APK menor ARM64; não altera o código, o modelo ou os controles. Os resultados JVM/GLSL cobrem as mesmas fontes. O empacotamento ARM64 é conferido separadamente por assemble, assinatura, manifesto, assets e comparação de DEX com o universal.

Ferramentas: JDK17, SDK/BuildTools34, Gradle8.5, AGP8.2.2, Kotlin1.9.22. Player Media3 `1.4.1`. `scripts/Verify.ps1` executa assemble/JUnit/lint em saída ASCII e copia APK/relatórios. Para reproduzir a verificação ARM64 use `scripts/Verify.ps1 -Abi arm64-v8a`; sem o argumento, gera o universal.

## Artefatos e integridade

APK ARM64 para o A14: `dist/LumaCamera-0.6-arm64-v8a-debug.apk`, **63.967.530 bytes** (~64 MB). SHA-256: `7271E4124851035D8C14E62E406798616294D9629BC1EB81996E035F173CE2BD`. Assemble ARM64 aprovado. Os **sete arquivos DEX são idênticos** aos do universal; modelo, LUTs e biblioteca `lib/arm64-v8a/libmediapipe_tasks_vision_jni.so` confirmados. Aapt confirmou somente ARM64 e as mesmas versão/permissões. Apksigner confirmou a mesma assinatura. Redução de aproximadamente 86 MB pela remoção de bibliotecas de outros processadores; nenhuma função/modelo foi removido.

APK universal: `dist/LumaCamera-0.6-debug.apk`, **149.906.354 bytes**. SHA-256: `12E16C2946A1B9E514DFE2629271823F43EF87A4CA7781026066E843FA21131C`.

Fonte completa: `dist/LumaCamera-projeto-0.6.zip`, incluindo modelo, licença, atribuição, scripts e testes. Exclui builds, dist, caches, local.properties e chaves de assinatura. Hashes individuais acompanham os APKs em `.apk.sha256`; `SHA256.txt` corresponde à entrega ARM64 desta etapa.

Certificado SHA-256: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`. A mesma identidade de desenvolvimento permite atualizar a instalação anterior por cima. Assinatura release/distribuição em loja permanece pendente.

Modelo MagicTouch: 6.227.884 bytes; SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`. A licença Apache 2.0 e a atribuição acompanham o arquivo. A inspeção estrutural confirmou entrada float32 `[1,512,512,4]` e saída sigmoid `[1,512,512,1]`; não foi uma execução de inferência. O wrapper usa confidence mask 0 para essa saída, sem confundir labels de categoria ou qualityScores com confiança.

Relatórios: `dist/TEST-*.xml`, `dist/lint-results-debug.html` e `dist/shader-verification.json`. Os avisos de lint incluem compatibilidade, tradução/acessibilidade e alocações de desenho. Relatórios gráficos mantêm `androidEglVerified=false` e `androidExternalOesVerified=false`.

## O que foi conferido

- **SubjectFocusPolicy (11 testes):** Pessoas automáticas, Objetos exigindo toque explícito, pontos inválidos, independência do foco físico, estado de espera/análise/perda e aplicação somente com máscara válida.
- **ObjectMaskPolicy (13 testes):** ponto e máscara alinhados nas quatro rotações, saída sigmoid, componente do alvo, rejeição de fundo/ruído/quadro inteiro, bordas suaves, ponto dentro de objetos vazados, movimento lento, rejeição de saltos e expiração. Um teste de anel grande parado reproduz e evita o erro de comparar centroide com um ponto projetado na borda.
- **Preferências:** novos controles permanecem independentes; coordenadas inválidas não selecionam um alvo central. A seleção é transitória e não é carregada do armazenamento.
- **Revisão concorrente:** ownership de RGBA e MPImage, fechamento de cliente serial, callback por geração, separação entre seleção de objeto e destino da transição, retorno da lente e rollback de ajuste físico. A perda é travada no worker de captura após a guarda de geração.
- **Regressões:** geometria, exposição, arquivos, player, LumaLog/LUT e compositor continuam passando. WebGL testa shaders reais com sampler 2D substituindo o OES de câmera; não testa Android EGL/OES ou MediaPipe.

O limite de três submissões/s do modelo de objetos não significa três resultados/s. O SDK mantém entrada interna 512×512 mesmo com readback menor. Máscaras têm força até 1.500 ms e expiram em 4.000 ms; alvos rápidos podem ultrapassar o acompanhamento assistido. Não há tracking de identidade nem profundidade editável.

## Teste prioritário no A14

1. Atualize por cima e comece em 720p/30. Confira orientação/proporção nas duas câmeras e o MP4 no player.
2. Com IA desligada, toque numa pessoa e depois num objeto. Observe a linha **Lente**; só o retorno AF confirma o foco. Teste manual e Voltar ao foco automático quando disponíveis.
3. Em **Foco e desfoque**, ative IA e escolha **Pessoas**. Depois escolha **Objetos** sem tocar: deve pedir seleção, sem inventar um recorte no centro.
4. Toque dentro de um objeto opaco e bem iluminado. Aguarde **objeto preservado**; compare contorno na prévia e no vídeo. Teste caneca/objeto vazado, movimento lento e perda/oclusão. Após perda, o app deve pedir seleção novamente e evitar escolher outro alvo sozinho.
5. Ligue Transição dinâmica. O primeiro toque seleciona o objeto; os seguintes mudam entre objeto e fundo. **Selecionar outro objeto** permite trocar o recorte. Reabrir/trocar câmera ou retornar ao app deve pedir nova seleção.
6. Troque Pessoas / Objetos e ajuste intensidade durante a sessão; confira que estados antigos não reaparecem e que a lente preserva sua escolha independentemente do automático IA.
7. Teste Log v2/assistência/LUT, biblioteca, busca/replay, áudio, compartilhamento e clipes longos. Meça atualização da máscara, FPS efetivo, atraso, bateria e aquecimento antes de aumentar resolução.

Guia: [FOCO_E_DESFOQUE.md](../guides/FOCO_E_DESFOQUE.md). Origem/licença/API: [REFERENCIAS.md](../development/REFERENCIAS.md). Histórico preservado: [0.5](VALIDACAO_0.5.md) e [0.4](VALIDACAO_0.4.md).
