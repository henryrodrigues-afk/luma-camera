# Foco e desfoque no Luma Camera 1.0

O app controla separadamente o foco da lente, o acompanhamento visual e o desfoque por IA. A lente ajusta a distância de nitidez conforme o hardware; a IA calcula um recorte de pessoas ou de um objeto escolhido. A máscara não mede profundidade nem confirma foco óptico. Abra **Ferramentas → Foco** para acessar esses controles.

## Começar com o ajuste automático

1. Em **Desfoque por IA**, toque em **Ajuste automático · Pessoas** ou **Ajuste automático · Objetos**.
2. Escolha **Natural · recomendado** para começar. Equilibrado e Forte aumentam a aparência do efeito.
3. Pessoas são recortadas automaticamente. Para Objetos, toque **dentro do alvo** na prévia. Esse toque identifica o objeto que deve ser preservado.
4. Confira o aviso da IA e faça um clipe curto com movimento. Compare a prévia com o MP4 no player interno.

| Aparência | Intensidade em Pessoas | Intensidade em Objetos |
| --- | --- | --- |
| Natural | 22% | 18% |
| Equilibrado | 36% | 32% |
| Forte | 52% | 46% |

O ajuste automático configura uma vez a intensidade, estabilidade, bordas, duração da transição e análise Leve; liga desfoque e acompanhamento por IA e desliga o desfoque oval. Não decide continuamente a intensidade pela distância do assunto. Os controles continuam independentes depois da ação.

Foco manual, trava da lente, balanço de branco, Log, estabilização e a chave da transição dinâmica mantêm sua escolha. O alvo do efeito volta ao assunto. Mudar apenas a aparência conserva um objeto já selecionado; mudar Pessoas/Objetos exige selecionar o objeto da cena. O app não escolhe um objeto novo sozinho.

## Ajustar intensidade e contorno

**Intensidade do desfoque IA** fica sempre visível. Para aparência mais discreta, comece com Natural e aumente aos poucos. Em **Refinamentos do desfoque**, os controles de estabilidade, suavidade das bordas e análise ficam recolhidos até serem abertos.

- **Estabilidade do contorno** reduz oscilações entre máscaras. Um filtro muito forte pode deixar o contorno atrás do movimento; não corrige uma segmentação errada.
- **Suavidade das bordas** controla a passagem entre assunto e fundo. Excesso pode diluir detalhes e gerar halos em cabelos, mãos ou objetos finos.
- **Análise IA: leve/qualidade**, em Pessoas, altera a resolução da análise. Qualidade exige mais processamento e não garante um recorte melhor em toda cena. Os ajustes automáticos começam em Leve.

Pessoas podem ser reunidas numa única máscara; pessoas sobrepostas não possuem identidade persistente. Objetos pequenos, lisos, transparentes, oclusos ou rápidos podem perder o alvo. Boa luz, foco da lente e separação visual do fundo ajudam o modelo. O efeito aplica desfoque a uma região definida pela máscara, sem mapa de profundidade ou vários planos de distância.

## Acompanhar um alvo sem desfocar

**Acompanhar assunto por IA** pode operar com **Desfoque por IA** desligado. Escolha Pessoas/Objetos e toque no alvo; o retículo indica o ponto acompanhado. Em Pessoas, o acompanhamento procura um componente coerente da máscara, para evitar o centro vazio entre pessoas separadas. Em Objetos, uma pequena região de referência desloca a seleção antes da inferência local.

Com lente automática e regiões AF disponíveis, a área de foco acompanha o alvo com suavização e limite de frequência. Com foco manual ou trava, o retículo pode acompanhar e a distância da lente fica fixa. Uma lente sem regiões AF pode oferecer acompanhamento visual sem mover a região física de foco.

Com acompanhamento ativo, um novo toque em Objetos troca o alvo. Ao aparecer **recorte perdido · selecione o objeto novamente**, toque dentro dele outra vez. A análise não assume silenciosamente outro objeto no ponto antigo. Trocar câmera/orientação, reabrir a câmera ou voltar ao app invalida a seleção anterior.

## Foco físico, trava e pontos A/B

Deixe **Foco manual da lente** desligado para usar autofocus. Toque na área desejada mesmo com a IA desligada. A linha **Lente** distingue pedido, confirmação e indisponibilidade; a confirmação vem de Camera2, conforme [CONTROL_AF_STATE](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#CONTROL_AF_STATE), e não da máscara.

**Foco manual da lente**, quando suportado, libera **Distância · infinito → perto**. **Travar foco da lente** mantém a posição alcançada conforme as capacidades da câmera. **Voltar ao foco automático** libera manual/trava. Alterações de cor ou desfoque não substituem essas escolhas.

**Foco A/B** memoriza distâncias reais da lente e executa uma transição de duração definida. Exige controle manual suportado; sem resultado real do sensor, o app não inventa uma distância. Toque na prévia, outra execução, parada, saída/reabertura ou rejeição de ajuste cancelam o movimento. Veja [Ferramentas profissionais](FERRAMENTAS_PRO_0.12.md).

## Transição dinâmica do efeito

Ligue **Transição dinâmica de foco** com o desfoque por IA ativo. **Duração da transição**, de 0,2 a 3 segundos, controla a mudança visual entre preservar o assunto e preservar o fundo. A lente segue seu próprio tempo e suporte físico.

**Preservar** permite escolher Automático, Pessoa/Objeto, Fundo ou seleção pelo toque. Preservar fundo inverte o efeito e desfoca o assunto recortado. Com apenas a transição ativa, os toques seguintes podem alternar o destino digital; para trocar um objeto, use **Selecionar outro objeto**. Com acompanhamento de Objetos ativo, tocar troca o alvo acompanhado; use o botão **Preservar** para escolher o fundo.

Desligar o desfoque também desliga essa transição; o acompanhamento e o foco da lente mantêm controles próprios. O efeito é gravado no MP4. Não se salva mapa de profundidade e não se troca o foco digital depois da gravação.

## Processamento local e atraso

Pessoas usam ML Kit Selfie Segmentation; objetos usam MagicTouch v1 com MediaPipe Tasks Vision `0.10.14`. Os modelos são locais e não precisam enviar imagens ou baixar pesos durante o uso. O modelo de objetos incluído possui licença Apache 2.0 em `assets/models`.

A análise aceita uma imagem por vez, descartando oportunidades de análise enquanto está ocupada. A cadência da máscara pode ser inferior à do vídeo: aumentar a gravação para 30 fps não significa 30 recortes novos por segundo. Resultados atrasados perdem contribuição no efeito, em vez de congelar indefinidamente a silhueta. A inferência, a resposta do filtro e o custo da GPU precisam ser medidos juntos no aparelho.

A política de pessoas começa a reduzir a contribuição quando a imagem analisada tem mais de 250 ms e a zera aos 600 ms. Para objetos, os limites são 450 e 1.200 ms. O tempo da inferência faz parte dessa idade: se o aparelho demorar demais, pode haver redução ou ausência temporária do efeito. Esses limites são proteção contra máscara velha, não promessa de resposta nesse tempo.

O blur calcula amostras da região que será desfocada, para reduzir vazamento de cores do assunto no fundo. Quando falta suporte de amostras, preserva pixels originais. Cabelos ou contornos mal classificados ainda podem produzir artefatos. O raio do desfoque oval é separado do raio da IA; combinar os dois aumenta o trabalho da GPU.

O [guia oficial de ML Kit](https://developers.google.com/ml-kit/vision/selfie-segmentation/android) recomenda modo de vídeo, máscara na resolução nativa do modelo e controle da frequência de análise. A tarefa de [segmentação interativa do MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/interactive_segmenter) usa uma indicação de região/alvo; isso não equivale a detectar automaticamente qualquer objeto nem a medir profundidade. Os contratos e modelos fixados pelo app podem diferir das versões atuais dessas APIs.

IA/desfoque continuam experimentais. Aparência natural, cabelos finos, movimento e latência no A14 precisam de comparação na prévia e no arquivo. Ajustes automáticos não são garantia de contorno perfeito. Consulte a [validação atual](../validation/VALIDACAO.md) e o [roteiro diário](../validation/TESTE_DIARIO_A14.md).
