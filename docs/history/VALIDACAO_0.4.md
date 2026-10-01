# Validação da base 0.4

Data: **01/10/2026**. Nome provisório: Luma Camera. Versão `0.4.0-portrait-log`, código de versão 4. Primeiro aparelho de referência: Galaxy A14 5G.

A 0.1 foi aberta pelo usuário no A14, com relato de que as funções pro não funcionaram. A 0.2 acrescentou processamento local; a 0.3 reorganizou a interface, persistiu ajustes e incluiu biblioteca/player. A 0.4 atende à direção de Retrato/Cinema e Log simulado com segmentação local, transição pessoa/fundo, foco ao toque e curva com LUT inversa.

**A 0.4 ainda não foi testada com câmera Android real, segmentação ML Kit, player ou MP4 no A14.** A integração final passou pelas verificações locais abaixo. Compilação e contratos sintéticos não equivalem à execução aprovada no celular.

## Estado da 0.4

| Verificação | Estado e alcance |
| --- | --- |
| `:app:assembleDebug` | Aprovado; APK universal de 87.385.339 bytes (~83 MiB), versão 4 / `0.4.0-portrait-log` |
| `:app:testDebugUnitTest` | 65 testes aprovados; 0 falhas e 0 erros |
| `:app:lintDebug` | 0 erros, 35 avisos; relatório HTML em `dist/lint-results-debug.html` |
| `scripts/VerifyShaders.cjs` | 24 contratos aprovados em WebGL/ANGLE; resultado em `dist/shader-verification.json` |
| ML Kit incorporado e permissões | APK inspecionado: modelo `.tflite`, quatro bibliotecas nativas e LUT presentes. Manifesto sem INTERNET e ACCESS_NETWORK_STATE; somente CAMERA, RECORD_AUDIO e permissão interna de receiver |
| Câmera, segmentação, foco e MP4 | Pendente de dispositivo; sem benchmark de A14 |
| Interface, biblioteca e LUT exportada | Código compilado; curva/LUT verificada por contratos, sem execução Android do seletor de documentos ou player |

O APK de desenvolvimento é `dist/LumaCamera-0.4-debug.apk`. `scripts/Verify.ps1` usa saída sem acentos em `%LOCALAPPDATA%/LumaCamera/verification`, executa compilação/JUnit/lint e copia APK, XML JUnit, HTML de lint e SHA256 para `dist`. A execução final concluiu com `BUILD SUCCESSFUL`; `apksigner verify --print-certs` confirmou a assinatura e `aapt dump permissions` confirmou o manifesto.

SHA-256 do APK 0.4: `007D282B880B7AEDAFF7E5F4D11ECF2B18814307D0E369398492D31ABCD03E64`. Certificado SHA-256: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`, igual ao das versões anteriores: permite instalar a atualização por cima sem desinstalar. A assinatura é de desenvolvimento, não de distribuição em loja.

Os avisos de lint concentram-se em textos ainda sem recursos de tradução, compatibilidade/acessibilidade de componentes nativos, alocações de desenho e uma análise conservadora do OutputStream da exportação (fechado pelo `bufferedWriter.use`). Não há erro bloqueante de lint; os avisos continuam no relatório para revisão.

Ferramentas fixadas: JDK 17, SDK 34, Build Tools 34.0.0, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. ML Kit Selfie Segmentation: `16.0.0-beta6`, API beta com modelo embarcado.

## Histórico confirmado

| Versão | Evidência local |
| --- | --- |
| 0.2 | Compilação aprovada, 30 JUnit, 12 contratos gráficos, lint 0 erros/15 avisos |
| 0.3 | Compilação aprovada, 37 JUnit, 12 contratos gráficos, lint 0 erros/32 avisos; APK 2.878.642 bytes |

SHA-256 do APK 0.3: `9CC12A1786A34520AC62101AF984010E0059C18B6F043962E9CB680150A7751B`. Certificado SHA-256 histórico 0.1/0.2/0.3: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`; correspondência com a 0.4 confirmada.

Emuladores oficiais AOSP API 29 x86_64/x86 não completaram boot no host na etapa 0.3: ADB permaneceu offline, sem APK executado, screenshot Android ou player verificado. Processos foram encerrados e drivers globais não foram alterados. Não foi feita nova tentativa de emulador nesta etapa 0.4.

## Cobertura de lógica e shaders

A suíte anterior cobre políticas de bitrate/FPS/sensor, exposição parcial, parâmetros neutros, coordenadas, normalização de preferências e caminhos privados. Os contratos da 0.4 acrescentam:

- **FocusMeteringPolicy:** 11 testes escritos para crop 16:9 sobre sensor 4:3, offsets/zoom, interseção, centro/cantos, valores NaN/infinito, regiões mínimas e dimensões inválidas. Testam matemática pura, não motor da lente, driver AF ou request Android.
- **PortraitMaskPolicy:** alinhamento/rotação entre Bitmap e GLES, confiança pessoa/fundo, suavização, expiração e progressão de transição. Testes sintéticos não demonstram precisão do modelo em pessoas reais.
- **SimulatedLog:** identidade, monotonicidade, inversa em forças completas/parciais, quantização e ordem/interpolação da LUT. Não calibram a cor do A14 ou recuperam sinal recortado pelo ISP.
- **Shaders reais:** contratos de cor, neutralidade, blur, nitidez, máscara e separação da assistência Log entre prévia/encoder passaram na execução final WebGL/ANGLE.

WebGL 2D não cobre textura externa OES, EGL Android, processamento ML Kit, readback no celular, orientação real, MediaRecorder, timestamps, áudio, MediaStore, player ou seletor de documentos. Esses itens permanecem no roteiro físico.

## Roteiro no A14

1. **Instalação e diagnóstico:** conferir assinatura final e instalar por cima. Autorizar/recusar câmera e áudio; exportar App → Recursos do aparelho → JSON. Registrar Android, lente, resolução, FPS alvo e parâmetros.
2. **Gravação limpa:** todos os efeitos desligados, clipe curto em 720p/30 e depois 1080p/30 quando oferecido. Conferir MP4, som, orientação, dimensões, proporção, enquadramento e biblioteca após reiniciar.
3. **Retrato IA:** ativar somente Retrato, perfil Leve e força moderada. Avaliar pessoa parada/andando, rosto, cabelo, dedos, oclusões, fundo detalhado e baixa luz. Conferir prévia e MP4; repetir frontal/traseira, Qualidade e modo avião.
4. **Sem pessoa e máscara atrasada:** remover a pessoa do quadro, mover rapidamente e observar se o efeito procura uma nova máscara em vez de congelar silhueta antiga. Testar mudanças de lente/rotação e desligar a chave durante análise. O efeito deve diminuir quando a máscara fica velha; a gravação limpa deve continuar em falha de IA.
5. **Cinema digital:** somente Cinema; automático deve priorizar pessoas com máscara válida. Tocar pessoa e fundo com transições de 0,2/1/3 s. Conferir mudança suave, retorno ao automático, chaves independentes e comportamento sem pessoa. Não interpretar a máscara como seleção de cada indivíduo ou mapa de profundidade.
6. **Foco físico:** tocar alvos próximos/distantes e nos quatro cantos, com autofocus disponível. Conferir mensagens de foco confirmado/refoco geral/lente sem suporte. Voltar ao automático; ligar foco manual e verificar que o toque não sobrescreve a distância. Comparar ponto de toque à região real em frontal/traseira e retrato/paisagem.
7. **LumaLog a 100%:** desligar Flat e demais efeitos. Gravar LumaLog com Assistência de prévia desligada; repetir ligada. O arquivo continua Log nos dois casos, enquanto a tela pode mostrar contraste restaurado. Exportar LUT `.cube` e aplicar no editor ao clipe SDR; comparar ao quadro limpo em iluminação estável.
8. **Log parcial e independência:** repetir força parcial com LUT da mesma força. Desativar Log e conferir imagem neutra; combinar Flat/ganho/temperatura e registrar que a LUT desfaz apenas Log. Mudar força durante o clipe exige tratamento por trecho, portanto priorizar força fixa por gravação.
9. **Demais efeitos e persistência:** testar individualmente oval, ganho, temperatura, contraste, saturação, nitidez e rastro. Combinar moderadamente; desligar uma chave preserva as outras. Reiniciar mantém parâmetros; trocar lente mantém efeitos e retorna controles físicos ao automático.
10. **Sensor, áudio e biblioteca:** quando suportados, ISO/obturador separados, EV, WB e foco manual. Testar áudio ligado/desligado/negado, botão de volume, player, compartilhamento, arquivo removido e recuperação de original preservado. Recusa do sensor deve restaurar ajuste físico anterior e preservar efeitos locais.
11. **Interrupções e desempenho:** saída, giro, pausa e pouco espaço; o app finaliza o clipe e não grava em segundo plano. Gravar 5–10 minutos em Leve/Qualidade; medir FPS efetivo, sincronismo, aquecimento, consumo, atraso da máscara e publicação. Reduzir para 720p, Leve e menos efeitos se necessário.

As cadências de 24/30 fps são alvos; 5/8 análises/s são limites de submissão do detector. Não foram medidas cadência constante, latência ou precisão no A14. MediaRecorder escolhe o encoder efetivo.

## Pendências

Validação física de interface, gravação, segmentação, foco, LUT, biblioteca e recuperação; adaptação automática de custo/aquecimento; tracking individual/objetos; profundidade editável; HDR/10 bits, RAW, scopes e estabilização. Nome/ícone, requisitos de distribuição e assinatura de release permanecem pendentes.

Log simulado, segmentação de pessoas e foco ao toque estão implementados como recursos da base, com limites explícitos. Apresentá-los como verificados no A14 exige registrar evidência de execução e inspeção do vídeo final.
