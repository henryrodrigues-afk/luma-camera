# Validação do Luma Camera 0.9

Data: **01/10/2026**. Versão **0.9.0-pro-tools**, código 9. APK **arm64-v8a** para testar no Galaxy A14 5G, Android 10 ou superior.

## Evidência local

| Verificação | Resultado |
| --- | --- |
| Compilação ARM64, JUnit e lint | BUILD SUCCESSFUL; **198 testes JVM**, zero falhas/erros; lint zero erros e 19 avisos |
| Shaders/LUT | **83 contratos aprovados** em WebGL/ANGLE, incluindo 25 novos contratos de monitor |
| Monitor | Gate desligado preserva pixels SDR/Log do encoder, opacidade zero preserva imagem, zebra/false color restauram SDR independentemente da assistência, contornos respeitam sensibilidade e regiões planas |
| Composição e ferramentas | Guias, área segura, horizonte adaptado à rotação e indisponibilidade; histograma sanitizado; ângulo limitado ao sensor/frame; bitrate, travas, pico e normalização independente |
| Continuidade | **25 arquivos idênticos à fonte 0.8**: player/biblioteca, armazenamento, geometria, Log/LUTs, modelos/licenças, máscaras e estabilização existentes |
| Assinatura | apksigner aprovado; certificado de desenvolvimento preservado |
| Manifesto e ABI | Código 9; mínimo 29/alvo 34; somente arm64-v8a; câmera, microfone e receiver interno; sem INTERNET/ACCESS_NETWORK_STATE |
| Android/A14 real | **Pendente**; nenhum aparelho conectado no ADB durante esta revisão |

Ferramentas: JDK 17, SDK/Build Tools 34, Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22. Dependências preservadas: Media3 1.4.1, ML Kit 16.0.0-beta6 e MediaPipe Tasks Vision 0.10.14. Execução: `scripts/Verify.ps1 -Abi arm64-v8a` e `node scripts/VerifyShaders.cjs`.

Os testes não medem câmera/EGL/OES Android, áudio/codec Samsung, inferência no celular, legibilidade nativa ou desempenho. WebGL executa os assets GLSL atuais com pixels sintéticos, substituindo o sampler externo OES por textura 2D. Não há medida de FPS, aquecimento, bateria, qualidade visual ou estabilidade de reprodução no A14.

## Mudanças da versão

Monitor Pro reúne histograma, zebra/limiar, false color, realce de contornos/sensibilidade, intensidade de indicadores, três grades, quatro proporções, área segura e nível. Todas as chaves são independentes. Os indicadores aparecem exclusivamente na interface ou na cópia da prévia; o encoder recebe assistência/monitor desligados.

O histograma mede uma amostra do sinal final com Log/efeitos/crop, antes da assistência SDR. Zebra/false color usam SDR restaurado mesmo quando a prévia permanece Log. A indicação de clipping é relativa ao sinal, não ao sensor. Contornos podem realçar textura/ruído e não confirmam foco óptico. As guias não recortam o arquivo. Veja [MONITOR_PRO.md](../guides/MONITOR_PRO.md).

O pipeline resolve/cacheia localizações de uniforms/atributo na inicialização e compacta monitores em dois vec4. O histograma cria target/readback apenas quando ligado e limita eventos a 4 Hz. Nível é limitado a 10 Hz e desliga ao pausar. Medidores usam desenhos nativos em cache, sem loop de animação. Ainda pode haver custo de GPU/readback/CPU quando ferramentas estão ativas.

Gravação ganha três orçamentos de compressão e medidor relativo do pico do microfone. O bitrate alvo é limitado pela faixa AVC na geometria final. Não é medição VBR nem promessa do codec efetivo escolhido pelo MediaRecorder. O painel atualiza o alvo ao entrar em REC, inclusive se já estiver aberto. O medidor só aparece quando o clipe realmente grava áudio, evitando estado falso após escolher Gravar sem áudio.

Câmera ganha travas AE/AWB físicas independentes, verificadas por característica/chave e aplicadas apenas no domínio automático. Ângulo do obturador calcula tempo conforme FPS/sensor; o painel informa milissegundos e ângulo efetivo escolhido, corrigindo a indicação quando o tempo não coincide com a escala discreta. O HUD continua mostrando exposição reportada pela câmera.

Layout horizontal reúne avisos de IA/estabilização lado a lado e separa a linha de áudio/tempo/histograma, corrigindo a sobreposição identificada na revisão. O histograma é limpo ao desligar ou sair de estados ajustáveis. A versão exibida em App vem dos metadados do pacote.

Player interno, persistência/recuperação dos vídeos, LumaLog v2, segmentação de objetos/pessoas, refinamento temporal e estabilização da 0.8 foram revisados e preservados. Não foi encontrado novo problema concreto de reprodução nesta etapa; o travamento/perda de áudio relatado ainda exige reprodução no aparelho e diagnóstico real. Não se afirma correção universal.

## Artefatos

APK: `dist/LumaCamera-0.9-arm64-v8a-debug.apk`, **64.620.150 bytes** (~64,6 MB).

```text
SHA-256: 2073CDA4AB8BF95C73EF80A6579749FBAC8E06AFEA11B87D9665E6A7C90EE5A3
Certificado SHA-256: 7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2
```

Fonte: `dist/LumaCamera-projeto-0.9.zip`, com fontes, testes, modelos, licenças, scripts e documentação. Exclui builds, dist, caches, local.properties e chaves. O ZIP e o APK têm arquivos SHA-256 ao lado. A assinatura permite atualizar versões anteriores com o mesmo certificado; é desenvolvimento, não uma assinatura de lançamento.

MagicTouch v1 preservado: 6.227.884 bytes, SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`, Apache 2.0 com atribuição.

Relatórios: `dist/TEST-*.xml`, `dist/lint-results-debug.html`, `dist/shader-verification.json`, `dist/capture-compatibility-0.9.json`. Avisos lint: SetTextI18n (9), GradleDependency (3), e um de cada ChromeOsAbiSupport, ClickableViewAccessibility, DataExtractionRules, Recycle, UnusedAttribute, UseSwitchCompatOrMaterialCode e ViewConstructor.

## Conferir no A14

1. Atualize por cima. Grave sem efeitos nas duas câmeras, vertical/horizontal. Confira proporção, orientação, áudio e player interno com início/meio/fim, arraste, ±5s, pausa/replay e retorno ao app.
2. Em Monitor Pro, ligue uma ferramenta de cada vez e compare com tudo desligado. Confira que indicadores/guias não aparecem no MP4. Teste também vídeo Log com assistência SDR ligada/desligada e LUT correspondente.
3. Confira horizonte/grades/proporções/área segura, especialmente com Log, desfoque e estabilização juntos em modo horizontal. Nível olhando para o céu/chão deve ficar indisponível.
4. Compare compressão Econômica/Equilibrada/Alta e bitrate/tamanho reais do MP4. Teste medidor com áudio, silêncio e Gravar sem áudio; não deve aparecer no clipe silencioso nem ser desenhado no vídeo.
5. Teste AE/AWB apenas quando disponíveis; compare automático, travado e preset/manual. Teste 180° em 24/30 fps e outros ângulos, conferindo o tempo reportado pela lente.
6. Compare estabilidade de máscara/recorte da 0.8 com ferramentas desligadas/ligadas. Meça clipes longos em 720p e no modo habitual para avaliar FPS, aquecimento e consumo. Se o player voltar a travar/perder som, salve o JSON pelo menu ⋮.

Histórico: [0.8](VALIDACAO_0.8.md), [0.7](VALIDACAO_0.7.md), [0.6](VALIDACAO_0.6.md), [0.5](VALIDACAO_0.5.md), [0.4](VALIDACAO_0.4.md). Fontes oficiais e GitHub: [REFERENCIAS.md](../development/REFERENCIAS.md).
