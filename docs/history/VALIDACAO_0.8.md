# Validação da base 0.8

Data: **01/10/2026**. Versão `0.8.0-steady`, código 8. Aparelho de referência: **Galaxy A14 5G**.

Esta etapa trata o **player interno do Luma**, acrescenta estabilização digital com assistência opcional do recorte IA e refina as máscaras de desfoque. Não foram acrescentadas dependências ou modelos. O recurso de estabilização começa desligado, mantendo o enquadramento anterior até ativação explícita.

**Não houve execução no A14.** ADB não encontrou dispositivo conectado. O travamento/perda de áudio relatado pelo usuário não foi reproduzido no host; as mudanças tratam causas possíveis e acrescentam diagnóstico real para identificar o defeito no aparelho. Não há medida de FPS, latência, bateria, aquecimento, precisão ou qualidade do MP4 Android.

## Resultados

| Verificação | Resultado |
| --- | --- |
| ARM64 assemble/JUnit/lint | BUILD SUCCESSFUL; **166 testes JVM**, zero falhas/erros; lint zero erros e 19 avisos |
| Shaders/LUT | **58 contratos aprovados**, assets atuais executados em WebGL/ANGLE com pixels sintéticos |
| Estabilização | Testes de translação, consenso espacial, exclusão por máscara IA, cortes, enquadramento limitado, suavização e pan |
| Desfoque | Filtro temporal adaptativo, histerese de pessoas, movimento forte, geometria/gap/geração/cena e perda real |
| Player | 13 testes de política: posição/intenção/lifecycle, replay, seek longo, limites/overflow, proporção e watchdog sem falsa recuperação em pausa |
| Compatibilidade da captura | 17 arquivos protegidos idênticos à fonte 0.7: políticas, geometria, Log/LUTs, modelos, arquivos/armazenamento e shaders de entrada/blur |
| Assinatura | apksigner aprovado; certificado de desenvolvimento igual ao da 0.7 |
| Manifesto/ABI | Código 8; Android mínimo 29, alvo 34; somente arm64-v8a; câmera, microfone e permissão interna de receiver; sem INTERNET/ACCESS_NETWORK_STATE |
| Android real | Pendente: reprodução dos clipes, áudio, EGL/OES, inferência, encoder e desempenho no A14 |

Ferramentas: JDK 17, SDK/BuildTools 34, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. Dependências preservadas: Media3 `1.4.1`, ML Kit `16.0.0-beta6`, MediaPipe Tasks Vision `0.10.14`.

Verificação integrada executada: `scripts/Verify.ps1 -Abi arm64-v8a`. Contratos gráficos: `node scripts/VerifyShaders.cjs`. WebGL substitui o sampler externo OES da câmera por textura 2D; não executa Android EGL/OES, codecs Samsung, AudioTrack ou modelos Android.

## Artefatos

APK: `dist/LumaCamera-0.8-arm64-v8a-debug.apk`, **64.103.026 bytes** (~64 MB).

```text
SHA-256: 5CB1F6C8BC60422A788C4A5F966845AD89BE211D0407BD94293F7B0D2C4214B6
```

Fonte: `dist/LumaCamera-projeto-0.8.zip`, com modelo/licença/atribuição, fontes, testes, scripts e documentação. Exclui builds, dist, caches, local.properties e chaves. Hash do APK em `.apk.sha256` e `dist/SHA256.txt`.

Certificado SHA-256: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`. Permite instalar por cima da versão anterior; é assinatura de desenvolvimento.

MagicTouch incorporado: 6.227.884 bytes; SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`, com licença Apache 2.0 e atribuição.

Relatórios: `dist/TEST-*.xml`, `dist/lint-results-debug.html`, `dist/shader-verification.json` e `dist/capture-compatibility-0.8.json`. Avisos de lint: SetTextI18n (9), GradleDependency (3), e um de cada ChromeOsAbiSupport, ClickableViewAccessibility, DataExtractionRules, Recycle, UnusedAttribute, UseSwitchCompatOrMaterialCode e ViewConstructor. A aprovação do build não elimina esses avisos.

## Player interno

SurfaceView usa viewport próprio sem recorte decorativo; controles persistentes ficam fora da imagem, com layout compacto em paisagem. Seek por arraste pausa e busca uma única vez ao soltar; replay, posição e intenção de pausa permanecem durante lifecycle/recriação. Mudo, volume de mídia do sistema zerado, trilha ausente e interrupção por outro app/fones têm estados separados.

Buffer local de 5–15 segundos, alvo de 24 MiB, fallback entre decoders e foco de áudio são configurados. Buffer parado por 12 segundos ou relógio/frames parados por 10 segundos podem provocar **uma** recuperação na posição salva. Host pausado, arraste e supressão por áudio não provocam recuperação automática. Persistência da falha oferece nova tentativa ou modo compatível interno, priorizando software disponível; esse modo pode ser mais lento.

O menu ⋮ salva diagnóstico JSON com eventos realmente observados, decoders, trilhas, erros, underruns, frames descartados e estado/posição. Esses contadores não são estimados nem validados em Android nesta etapa. Veja [REPRODUCAO.md](../guides/REPRODUCAO.md).

## Desfoque e estabilização

O filtro temporal substitui a mistura fixa adicional aplicada anteriormente pela GPU. Atua uma vez nos segmenters, com tempo de resposta ajustável, adaptação a mudanças fortes, histerese de presença e descarte do histórico inválido. Componentes/seed/perda de objetos continuam baseados na máscara raw atual; não se cria identidade nem se aceita outro alvo após perda. Suavidade das bordas controla a mistura no compositor. Taxas/modelos da segmentação e expiração dos resultados foram preservados.

A estabilização estima translação por blocos e consenso espacial em imagem limpa de baixa resolução. O recorte IA já ativo pode excluir o assunto da referência. Um filtro causal, com acompanhamento de pan e limite de crop, publica a mesma correção para prévia/MP4. O foco ao toque aplica o snapshot do sampler, preservando alinhamento após recorte, giro e espelho. Correção desligada usa o caminho antigo da cópia, sem readback de movimento.

O zoom de 1,04×–1,25× conserva proporção/dimensões do arquivo, mas reduz campo de visão e pode reduzir detalhe. A função não corrige rotação, rolling shutter, perspectiva, caminhada forte ou borrão; não usa rede neural própria de estabilização. Pouca textura/luz, cortes ou consenso fraco reduzem a correção. A análise acrescenta custo quando ativa. Veja [ESTABILIZACAO.md](../guides/ESTABILIZACAO.md) e [FOCO_E_DESFOQUE.md](../guides/FOCO_E_DESFOQUE.md).

Resolução/FPS alvo, bitrate, codec, curva Log, modelos e timestamps do encoder não foram alterados. Os shaders de composição/cópia foram alterados para os recursos solicitados; **não se afirma que o material seja idêntico quando ativados**, nem que não haja impacto de desempenho no aparelho.

## Conferir no A14

1. Atualize por cima e reproduza dentro do Luma um clipe que antes travava/perdia áudio. Teste começo/meio/fim, ±5s, arraste, pausa/replay, rotação, retorno ao app e volume/fones. Se repetir, salve o diagnóstico pelo menu ⋮.
2. Grave um clipe novo em 720p/30 e outro na resolução habitual, com áudio. Diferencie trilha ausente de reprodução interrompida; compare no player interno e confira duração/áudio da gravação.
3. Compare contorno Pessoas/Objetos com estabilidade 0/65/90% e bordas baixas/altas, parado e em movimento. Observe se reduz oscilação sem manter contorno antigo, além da perda/nova seleção de objeto.
4. Compare estabilização desligada/ligada, pequenos tremores, pan lento, objeto em movimento, pouca luz e fundo sem textura. Confira tanto prévia quanto MP4, nas duas câmeras e orientações. Toque nos cantos com crop ativo e confira foco/seleção.
5. Teste Log v2 com assistência SDR ligada/desligada e LUT; o MP4 mantém Log. Grave um clipe longo para medir FPS efetivo, latência, bateria e aquecimento antes de aumentar o custo dos efeitos.

Histórico: [0.7](VALIDACAO_0.7.md), [0.6](VALIDACAO_0.6.md), [0.5](VALIDACAO_0.5.md) e [0.4](VALIDACAO_0.4.md). Fontes primárias: [REFERENCIAS.md](../development/REFERENCIAS.md).
