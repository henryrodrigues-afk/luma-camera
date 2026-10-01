# Validação da base 0.7

Data: **01/10/2026**. Versão `0.7.0-studio`, código 7. Aparelho de referência: **Galaxy A14 5G**.

A 0.7 renova a interface da câmera, os ajustes, a biblioteca e o player. As otimizações reduzem atualizações de texto iguais, persistência repetida durante arraste e decodificação de miniaturas fora da área visível. Não foram acrescentadas dependências de interface nem alteradas as configurações de qualidade da captura.

**Esta versão ainda não foi executada no A14.** Não há dispositivo conectado para medir fluidez, FPS efetivo, latência, consumo, aquecimento ou qualidade do MP4 no Android. Compilação, testes JVM e mockup HTML não substituem essa execução.

## Resultado final

| Verificação | Resultado |
| --- | --- |
| APK ARM64, assemble/JUnit/lint | BUILD SUCCESSFUL; **121 testes JVM**, zero falhas/erros; lint zero erros e 20 avisos |
| Preservação da captura | **21 arquivos idênticos byte por byte** à fonte 0.6: pipeline, políticas, modelos, shaders e LUTs |
| CameraEngine | Revisão do diff: somente deduplicação da publicação de ISO/exposição ao HUD; metadados de captura e automação preservados |
| Versão/permissões | Código 7 / `0.7.0-studio`, Android mínimo 29, alvo 34; CAMERA, RECORD_AUDIO e permissão interna de receiver; sem INTERNET/ACCESS_NETWORK_STATE |
| Assinatura | apksigner aprovado; certificado de desenvolvimento igual ao da 0.6 |
| Empacotamento | Somente arm64-v8a; modelo, licença, atribuição, LUTs e biblioteca JNI confirmados no APK |
| Referência visual | Mockup HTML renderizado em PNG; composição inspecionada e overflow conferido pelo script. Não é captura do app Android |
| GLSL/LUT | Evidência anterior: 54 contratos aprovados na 0.6. Não reexecutados nesta etapa; todos os assets correspondentes são idênticos |
| Android/A14 | Pendente: câmera/OES, encoder, inferência, reprodução e desempenho real |

Ferramentas: JDK 17, SDK/BuildTools 34, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. Player Media3 `1.4.1`, ML Kit `16.0.0-beta6` e MediaPipe Tasks Vision `0.10.14`, sem atualização de dependências nesta etapa.

`scripts/Verify.ps1 -Abi arm64-v8a` executou assemble/JUnit/lint com as fontes finais. A saída de build usa caminho ASCII para evitar problemas dos workers Java com acentos. Sem `-Abi`, o script permite compilar o APK universal; **o artefato entregue nesta etapa é ARM64**.

## Artefatos e integridade

APK para o A14: `dist/LumaCamera-0.7-arm64-v8a-debug.apk`, **63.992.242 bytes** (~64 MB). SHA-256:

```text
CB32B411D13B60CA4EB173F335D03B292F94AE150659CB88B4E8F21081F354F8
```

Fonte: `dist/LumaCamera-projeto-0.7.zip`, incluindo modelo, licença, atribuição, testes, scripts, documentação e referência visual HTML. Exclui builds, dist, caches, local.properties e chaves de assinatura. O hash individual acompanha o APK em `.apk.sha256`; `dist/SHA256.txt` corresponde a esta entrega ARM64.

Certificado SHA-256: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`. A mesma identidade permite atualizar a instalação anterior por cima. A assinatura é de desenvolvimento.

Modelo MagicTouch: 6.227.884 bytes; SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`, com licença Apache 2.0 e atribuição embarcadas.

Relatórios: `dist/TEST-*.xml`, `dist/lint-results-debug.html` e `dist/recording-preservation-0.7.json`. O relatório gráfico anterior permanece em `dist/shader-verification.json` e não comprova EGL/OES Android. O lint registra 20 avisos: SetTextI18n (9), GradleDependency (3) e um de cada ChromeOsAbiSupport, ClickableViewAccessibility, DataExtractionRules, Recycle, SwitchIntDef, UnusedAttribute, UseSwitchCompatOrMaterialCode e ViewConstructor. Não houve erros de lint.

## O que a revisão protegeu

- Resolução, FPS alvo, bitrate, codec, geometria, curva Log, LUT, modelos e compositor não foram alterados. A comparação de hashes está em `recording-preservation-0.7.json`.
- Os sliders enviam o ajuste imediatamente à captura. A espera de 250 ms atua somente na persistência; soltar, fechar o painel ou pausar o app conclui a gravação das preferências.
- O HUD mantém a leitura inicial e o intervalo de 500 ms; só deixa de publicar pares ISO/exposição iguais. Os metadados de automação continuam atualizados em cada resultado da câmera.
- A entrada do painel dura 160 ms, respeita as animações do sistema e não ocorre durante gravação. Ícones usam caminhos reutilizados e ripple nativo, sem animação contínua.
- Miniaturas usam fila própria, cache limitado a 6 MiB, até dois trabalhos pendentes e janela visível com margem. Não compartilham a fila de consulta/compartilhamento/recuperação de vídeos.
- Os 121 testes cobrem políticas de captura, geometria, exposição, foco/máscaras, Log, arquivos, reprodução e preferências. Não são testes instrumentados da nova interface.

## Teste prioritário no A14

1. Atualize por cima e abra a câmera em retrato e paisagem. Confira atalhos, rolagem dos ajustes, tamanho de fonte e visibilidade dos estados da lente/IA.
2. Grave em 720p/30, depois na resolução que costuma usar. Confira prévia, MP4, proporção, orientação, áudio e duração nas duas câmeras. Compare clipes com os mesmos ajustes da 0.6.
3. Arraste um ajuste de imagem, feche o painel e reabra; depois saia e retorne ao app. O efeito deve responder durante arraste e o valor deve permanecer salvo. Confira que os controles individuais continuam independentes.
4. Grave em LumaLog v2 a 100%, alternando somente o monitor SDR. O MP4 deve manter Log, e a LUT v2 deve restaurar contraste/cor como na versão anterior.
5. Teste Pessoas e Objetos, seleção por toque, perda do alvo e transição dinâmica conforme o [guia de foco](../guides/FOCO_E_DESFOQUE.md). A confirmação da lente permanece separada do recorte IA.
6. Abra a Galeria, role uma lista de vídeos e reproduza um clipe. Confira play/pausa, avanço, replay, retorno à mesma posição da lista, compartilhamento e recuperação de originais.
7. Grave um clipe longo com os efeitos que utiliza. Meça FPS efetivo, atraso, bateria e aquecimento antes de concluir que houve ganho de fluidez no A14.

Veja [design](../development/DESIGN.md), [arquitetura](../development/ARQUITETURA.md) e [referências](../development/REFERENCIAS.md). Histórico: [0.6](VALIDACAO_0.6.md), [0.5](VALIDACAO_0.5.md) e [0.4](VALIDACAO_0.4.md).
