# Validação da base 0.5

Data: **01/10/2026**. Versão `0.5.0-fixes`, código 5. Aparelho de referência: **Galaxy A14 5G**.

O usuário relatou distorção/inversão nas duas câmeras, na prévia e no vídeo salvo, problemas no player e pouca diferença do Log. A 0.5 centraliza a geometria da câmera/saída/toque, troca o player por Media3, acrescenta crominância reversível ao Log e funde funções relacionadas nos menus.

**A 0.5 ainda não foi executada no A14.** Não há aparelho ADB conectado. Resultados de lógica/WebGL demonstram contratos locais; não demonstram câmera Camera2/OES, encoder MediaRecorder, modelo ML Kit ou reprodução real Android. Os emuladores tentados na etapa 0.3 não completaram boot neste host; não foram repetidas essas tentativas.

## Verificações finais

| Verificação | Resultado |
| --- | --- |
| Assemble/JUnit/lint | BUILD SUCCESSFUL; 95 JUnit aprovados, 0 falhas/erros; lint 0 erros e 31 avisos |
| Shaders e LUT | 54 contratos aprovados: 50 pixels/GLSL e 4 verificações LUT |
| Assinatura/versão/permissões | apksigner aprovado; código 5 / `0.5.0-fixes`; CAMERA, RECORD_AUDIO e permissão interna de receiver; sem INTERNET/ACCESS_NETWORK_STATE |
| Aparelho real | Pendente; nenhum teste de câmera/MP4/player da 0.5 |

APK: `dist/LumaCamera-0.5-debug.apk`. Fonte: `dist/LumaCamera-projeto-0.5.zip`. LUT v2 a 100%: `dist/LumaLog-v2-to-SDR-33.cube`. Ferramentas fixadas: JDK17, SDK/BuildTools34, Gradle8.5, AGP8.2.2, Kotlin1.9.22. Modelo embarcado ML Kit `16.0.0-beta6`; player Media3 `1.4.1`.

APK universal: **94.889.246 bytes (~90,5 MiB)**. SHA-256: `D29E06F75F55238A0E3D4BB605C090271615A3121C5D0759FC9A445CAF45B610`. Certificado SHA-256: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`, igual ao das versões anteriores, permitindo atualizar por cima. A inspeção do pacote confirmou modelo `.tflite`, LUTs v1/v2 e shaders finais. A assinatura é de desenvolvimento.

Relatórios: `dist/lint-results-debug.html`, XMLs JUnit `dist/TEST-*.xml` e `dist/SHA256.txt`. Avisos de lint incluem tradução/acessibilidade, alocações de desenho, compatibilidade/versões de dependências e enum de estado do player (STATE_IDLE permanece sem ação própria); não são prova de execução móvel aprovada. Exceções reais do player têm mensagem e retentativa.

Build final usa `scripts/Verify.ps1`, com saída ASCII em `%LOCALAPPDATA%/LumaCamera/verification`. Copia APK, relatórios JUnit/lint e SHA256 para dist. O primeiro build passou assemble/JUnit e teve uma leitura de lint durante edições; a execução final foi reiniciada com as fontes estáveis, sem desativar checks nem adicionar baseline.

## Alcance dos testes

- **FrameGeometry:** proporção FIT em retrato/paisagem, dimensões finais, cantos assimétricos, giro e espelho da prévia, equivalência UV/toque, barras sem alvo, resize e normalização de oito transformações HAL. Preserva crop/Y-flip, mantém produtor sem rotação automática e conserva matrizes não ortogonais em fallback.
- **VideoPlaybackPolicy:** posição e intenção ao pausar/sair/retornar, duração desconhecida, conclusão/replay e checkpoints inválidos. Não instancia nem decodifica Media3 no Android.
- **SimulatedLog:** 13 contratos JVM, incluindo v1, v2 crominância, inversa de RGB, forças parciais, cinzas, quantização e tabela 3D. Botão de preparação preserva exposição/foco e desfoques, desligando explicitamente ajustes criativos; escolhas posteriores permanecem individuais.
- **Shaders reais:** cor/neutralidade, blur/retrato, Log/assistência, encoder sem assistência e oito combinações de rotação/espelho. Cadeia do corpo real de `camera.frag` para `copy.frag` usa textura 2D no lugar do sampler OES externo, com cantos assimétricos; nove contratos adicionais conferem a conversão vertical e as saídas orientadas.
- **LUTs:** v1 preservada byte por byte. V2 testa 729 cores quantizadas em 8 bits em três configurações. Erro máximo de retorno por canal: aproximadamente 1,86/255 na LUT33 a 100%; 0,87/255 na LUT65 a 45%; 1,29/255 na LUT65 a 75%. São pixels sintéticos, sem compressão H.264/ISP real.

Relatório gráfico: `dist/shader-verification.json`, com `androidEglVerified=false`, `androidExternalOesVerified=false` e `cameraShaderTextureTargetSubstituted=true`. OES/EGL, CPU/GPU móvel, decoders, fluxo de documentos e MP4 continuam fora do alcance desses contratos.

## Roteiro prioritário no A14

1. **Atualização:** instalar por cima, sem desinstalar. Conferir versão 0.5. Autorizar câmera/microfone; exportar App → Recursos do aparelho → JSON.
2. **Geometria:** desligar efeitos e usar 720p/30. Em cada câmera, filmar texto e uma forma circular nas quatro orientações do aparelho. A prévia deve preservar proporção; a frontal é espelhada só na tela. Conferir topo/baixo, texto/cantos e enquadramento no MP4 interno e em outro player.
3. **Encoder:** repetir 1080p/30 se oferecido. A preferência é giro físico; em hardware que só oferece dimensões originais, usa uma orientação no MP4. Se falhar, registrar mensagem, resolução, câmera e o log LumaGeometry (matriz nativa, dimensões e caminho do encoder).
4. **Player:** testar clipe novo e clipes anteriores: abrir, pausar, buscar, ±5 s, conclusão/replay, reabrir o mesmo clipe, alternar clipes, sair/retornar e girar tela. Posição e intenção devem ser preservadas. Remover um arquivo de teste pela galeria e conferir mensagem/retentativa; não apagar gravações necessárias.
5. **Log v2:** Imagem → Preparar imagem para edição. Filmar cores, rosto e cinzas a 100%, primeiro sem monitor SDR e depois com ele. O clipe deve ficar flat/dessaturado nos dois casos. A tela mostra SDR apenas com o monitor ligado e o aviso TELA SDR. Aplicar a LUT v2 correspondente no editor. Não usar LUTs de AppleLog/SLog/LogC.
6. **Força e legado:** testar força parcial com LUT exportada da mesma força; v1 com LUT v1. Perfil/força não mudam durante gravação. Conferir sufixo do arquivo e persistência depois de reiniciar.
7. **Menus e desfoque:** Imagem reúne Log/Flat/cor; Foco e retrato reúne lente/IA. Desfoque por IA funciona estático; Transição dinâmica adiciona mudança suave de alvo. A intensidade/qualidade é única. Desligar a base desliga a transição, preservando outros efeitos.
8. **Foco e recursos:** toque na prévia, retorno ao AF e foco manual quando disponível. Testar todos os controles individualmente, máscara em pessoa/fundo/mãos/cabelo/movimento, frontal/traseira, áudio, biblioteca, compartilhamento e recuperação de original privado.
9. **Sessões longas:** 5–10 minutos em Leve/720p e depois configurações maiores. Avaliar fluidez, atraso, aquecimento, áudio, armazenamento e interrupções. 24/30 FPS e 5/8 análises/s são alvos/limites de submissão, não medições do A14.

## Histórico

Evidências da 0.4 foram preservadas em [validação 0.4](VALIDACAO_0.4.md). Correspondência do certificado de desenvolvimento com o APK 0.5 confirmada.

O efeito de Log usa SDR de 8 bits e não recupera informação recortada pelo ISP. A normalização de matriz cobre o padrão ortogonal observado nos exemplos oficiais; particularidades do HAL Samsung precisam de execução. Nome/ícone finais, SDK/requisitos de loja e assinatura release continuam pendentes.
