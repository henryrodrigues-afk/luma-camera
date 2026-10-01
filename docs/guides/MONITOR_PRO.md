# Monitor Pro e controles de gravação — 1.0

Cada ferramenta tem sua própria chave. Histograma, zebra, false color, realce de contornos, grade, guias, área segura, nível e medidor aparecem exclusivamente na interface; não são desenhados no MP4. A assistência SDR do Log continua em Imagem, junto ao perfil, porque controla como a imagem Log é vista.

## Exposição e foco na prévia

Em **Ferramentas → Exposição**, **Ajustar exposição automaticamente** desliga ISO e obturador manuais, libera a trava AE e desliga o ganho digital. Aplica um viés próximo de **−1/3 EV** somente quando a faixa/passo da lente permite um ajuste moderado, de no máximo −0,5 EV; caso contrário, usa compensação neutra. Foco manual/trava, balanço de branco, Log, estabilização e os efeitos restantes são preservados. É uma ação explícita de preparação; cada controle pode ser ajustado individualmente depois.

A câmera continua medindo a cena. Compare realces com zebra e aparência com/sem assistência SDR ou LUT de prévia. Ganho digital aumenta brilho depois da captura; não recupera realces cortados, e Log também não os restaura. Em contraluz, um viés negativo pode escurecer o rosto: confira o assunto e ajuste EV conforme a cena. Os novos valores padrão de ganho/temperatura são zero e contraste/saturação são 1; escolhas explícitas já salvas continuam preservadas.

| Ferramenta | Como usar | Sinal analisado |
| --- | --- | --- |
| Histograma | Observe distribuição das sombras à esquerda e altas luzes à direita. A marca inferior é a média. Sombra/Luz são frações próximas a 0/100% do sinal amostrado. | Luminância RGB aproximada do sinal final com efeitos, Log e recorte da estabilização; antes da assistência SDR e dos indicadores |
| Zebra | Ajuste o limiar de 50–100%. A faixa listrada marca luminância acima do limiar. Padrão 95%. | SDR restaurado quando Log está ativo, mesmo com assistência SDR desligada |
| False color | Compare as bandas da legenda para localizar regiões claras/escuras. Ajuste a intensidade dos indicadores. | Mesmo SDR restaurado da zebra |
| Focus peaking | Aumente sensibilidade para destacar mais contornos; compare o detalhe enquanto ajusta o foco da lente. | Contraste local com quatro vizinhos e compensação aproximada da curva Log |

False color usa roxo nas sombras profundas, azul/ciano nos tons escuros, cinza/rosa na faixa central, verde nos meios-tons claros, amarelo/laranja nos claros e vermelho nas altas luzes. As bandas são uma referência própria de luminância SDR, sem calibração IRE. Não use o verde ou o rosa como promessa de exposição correta de pele.

O histograma possui 64 bins e usa uma amostra GPU 64×36 até quatro vezes por segundo. É uma aproximação da distribuição, não um histograma RAW do sensor nem uma análise de cada pixel do arquivo. Com Log, o piso/teto da curva podem afastar o sinal de 0/100%; as porcentagens do histograma não garantem ausência de clipping no SDR original. A zebra também não detecta necessariamente um canal RGB isolado estourado.

Contornos auxiliam a avaliação visual, mas textura e ruído também podem aparecer. Não confirmam foco óptico. A confirmação da lente continua no aviso Camera2 quando suportada. Com Log parcial, a restauração requer trabalho adicional do shader. Histograma acrescenta um readback pequeno; desligue ferramentas dispensáveis em celulares limitados. Desligadas por padrão, não acionam análise extra do monitor.

## Composição

Escolha Terços, Cruz central ou Proporção áurea. O atalho da tela principal liga/desliga a grade; o estilo fica salvo. Guias 1:1, 9:16, 16:9 e 2,39:1 cabem centralmente na imagem. A área segura mostra 90% da guia, ou do frame quando não há guia. Grade e área segura permanecem independentes.

Os guias não mudam a resolução, não recortam a gravação e não alteram o toque de foco. Faça o recorte final no editor se quiser aquela proporção. O nível usa vetor de rotação, gravidade ou acelerômetro disponível, adaptado à rotação da tela, até dez atualizações por segundo. Verde indica até 1,5° do horizonte. A leitura fica indisponível quando a orientação não define um horizonte confiável, como olhando quase diretamente para o céu/chão. O sensor é desligado quando o app pausa ou o nível é desativado.

## Obturador, travas e compressão

Em Câmera, escolha ângulos 45°, 90°, 180°, 270° ou 360°. O tempo é calculado por `ângulo / 360 / FPS` e limitado ao sensor/período do frame. 180° equivale aproximadamente a 1/60 s em 30 fps, 1/48 s em 24 fps. A ação ativa somente o obturador manual; o ISO mantém sua escolha. O painel mostra o tempo e ângulo solicitados efetivamente após os limites; o HUD de exposição mostra os valores que a câmera reporta. O recurso depende de exposição manual oferecida pela lente.

AE trava exposição automática quando ISO/obturador estão automáticos. AWB trava balanço automático quando nenhum preset manual está selecionado. São independentes, verificadas por característica e chave Camera2. Uma trava selecionada pode ficar inativa enquanto o respectivo controle estiver manual. Desligue a trava para retomar a automação. Não se simula uma trava física ausente com brilho/cor digital.

Gravação oferece Econômica (0,6×), Equilibrada (1×) e Alta (1,5×) do orçamento base de bitrate. A seleção não altera resolução/FPS. O alvo é limitado pela faixa AVC na geometria final antes da preparação. O codec efetivo ainda é escolhido pelo MediaRecorder. O painel mostra **alvo estimado** antes de REC e **alvo configurado** durante REC; não é medição do bitrate variável do arquivo. A qualidade fica fixa durante o clipe. Mais bitrate ocupa espaço e pode reduzir compressão; não cria detalhe ou latitude ausente no sensor.

## Áudio e reprodução

Medidor de pico usa `MediaRecorder.maxAmplitude`, aproximadamente quatro vezes por segundo, somente quando o clipe grava áudio e a ferramenta está ativa. O alerta PICO ALTO é relativo à escala assumida do gravador. Não representa medição calibrada de dBFS, clipping AAC ou ganho manual. Desligá-lo não desliga a gravação de áudio. Não há segundo captador nem processamento de áudio adicionado.

O player interno preserva as correções da 0.8: SurfaceView com proporção FIT, controles persistentes, seek ao soltar, posição/pausa preservadas, foco de áudio, estados de mudo/interrupção/volume, recuperação limitada e modo compatível. Se um vídeo continuar travando/perdendo som no A14, o menu ⋮ permite salvar um diagnóstico real. Veja [REPRODUCAO.md](REPRODUCAO.md).

## Waveform, RGB parade, vectorscope e LUT — 0.12

As chaves independentes de waveform/RGB parade/vectorscope estão em Monitores. Compartilham uma leitura de 64×36 a até 4 Hz com o histograma. Medem o sinal destinado ao MP4, inclusive Log, antes da assistência SDR e da LUT. Os painéis de tela não são gravados. Em janelas pequenas ficam acessíveis no menu.

A LUT importada fica em Imagem → LUT na prévia, apenas na tela. Converter Log para SDR define a entrada da LUT: ligue para um look criativo que espera SDR; desligue para uma LUT que já converte LumaLog. O menu indica a entrada atual. Veja o [guia 0.12](FERRAMENTAS_PRO_0.12.md).
