# Estabilização local — Luma 0.13

Abra **Ferramentas → Estabilização**, busque “tremor” ou toque no aviso de estabilização na câmera. Você pode fixar essa função em um dos três atalhos. A correção aparece na prévia e no MP4.

## Escolher o movimento

- **Equilibrado:** ponto de partida para gravação com o celular na mão. Suaviza oscilações e acompanha uma panorâmica sustentada.
- **Câmera parada:** segura mais a correção de pequenas oscilações quando você tenta manter um enquadramento fixo. Não equivale a um tripé.
- **Em movimento:** acompanha a direção intencional mais rapidamente, para panorâmicas e deslocamentos. Não elimina balanço forte de caminhada.

Escolher o tipo não altera sua força, suavidade, recorte ou análise. Essas escolhas continuam independentes e ficam salvas, inclusive nos presets.

## Ajustes

- **Estabilização digital:** liga a correção antes de gravar.
- **Compensar pequenos giros:** acrescenta correção da rotação estimada na imagem. Respeita a margem do recorte; não nivela automaticamente o horizonte nem depende de giroscópio. Desligar essa chave libera a reserva para corrigir mais deslocamento horizontal/vertical, mantendo o mesmo recorte.
- **Força:** quantidade de correção aplicada. Em 0%, conserva apenas o recorte, sem corrigir deslocamento ou giro.
- **Suavidade:** valor maior segura mais as oscilações; menor acompanha movimentos intencionais mais rapidamente.
- **Recorte de segurança:** zoom fixo entre 1,04× e 1,25×. Mais margem permite mais correção. A proporção e as dimensões do MP4 são preservadas, mas o campo de visão e o detalhe disponível diminuem.
- **Análise Leve / Precisa:** lado máximo de 96/128 px e até 10/15 análises por segundo. Só uma tarefa fica em voo, sem fila de quadros antigos. Esses limites não são medições de FPS no A14.
- **Priorizar fundo com recorte IA:** com acompanhamento ou desfoque ativo e máscara recente, evita usar o assunto em movimento como referência de câmera. Sem máscara válida, analisa a imagem normalmente. Não liga a IA ou o desfoque automaticamente.

Escolha tipo, giros, recorte e análise antes de REC. Força e suavidade continuam ajustáveis durante a gravação. Para acompanhar um objeto sem desfocar, ligue **Foco → Acompanhar assunto por IA**, escolha Objetos e toque no alvo.

## Começar e comparar

Comece com **Equilibrado, Leve, força 70%, suavidade 55% e recorte 1,12×**. Grave um clipe com a função desligada e outro da mesma cena com ela ligada. Esses são valores iniciais para comparação, sem promessa de desempenho medido no aparelho.

Se o enquadramento resiste à panorâmica, experimente Em movimento ou reduza suavidade. Se você tenta manter o celular parado, experimente Câmera parada. Aumentar recorte abre margem para deslocamento e rotação, mas não resolve falta de textura, borrão ou pouca luz. Teste os ajustes individualmente.

## O que mudou na 0.13

A estimativa de movimento usa comparação em múltiplas escalas e consenso distribuído de pequenos blocos para separar movimento da câmera de referências inconsistentes. Além da translação, estima uma pequena rotação no espaço físico da imagem, considerando sua proporção.

O cálculo de luminância e movimento passa para um executor CPU próprio. O readback reduzido continua na GPU; apenas um trabalho é submetido, com rejeição de resultados antigos ou de outra sessão. O filtro é causal: não espera quadros futuros e mantém os timestamps de gravação.

Confiança, trajetória e tempo entre amostras orientam a suavização. Uma perda comum de referência reduz a correção gradualmente; a retomada também é gradual. Cortes de cena descartam a trajetória antiga. Panorâmicas e limites de margem têm tratamento próprio para evitar acumular uma correção escondida fora do recorte.

O recorte não aumenta e diminui a cada quadro. As quatro bordas limitam conjuntamente rotação e deslocamento. Prévia, encoder, scopes, toque e retículo usam a mesma transformação, aplicada após os efeitos. Máscara e imagem permanecem no mesmo espaço antes dessa amostragem.

## Limites reais

A correção básica é visão computacional clássica, com implementação própria em Kotlin/GLES. A assistência IA aproveita somente máscaras já ativas; não existe uma rede neural de estabilização, integração de giroscópio ou dependência de EIS/OIS.

Não corrige rolling shutter, borrão de movimento, perspectiva ou grandes movimentos 3D. Pouca textura/luz, parallax, um assunto ocupando quase toda a imagem ou movimento além da busca podem reduzir a correção. Compensar pequenos giros não significa alinhar o horizonte ao nível.

Análise fora do worker de renderização evita que esse cálculo ocupe a fila da GPU, mas readback, cópia e processamento ainda custam tempo e memória. Fluidez, sincronismo, temperatura e ganho visual precisam ser medidos no A14, principalmente junto com desfoque IA e scopes. Comece a comparação em 720p/30.

## Validar no A14

Grave e reproduza clipes novos nas duas câmeras, em retrato/paisagem:

1. Celular quase parado, com pequenos tremores: compare os três tipos de movimento.
2. Giro leve do punho: compare a chave de giros, procurando bordas vazias ou deformação.
3. Panorâmica lenta, parada e retorno: observe travamento da direção ou salto ao recentralizar.
4. Pessoa/objeto andando diante de fundo com textura: compare a assistência de fundo com IA.
5. Cubra brevemente a câmera, descubra e faça um corte de cena: observe perda e retomada.
6. Toque nos cantos e acompanhe um alvo: seleção e retículo devem corresponder à imagem corrigida.
7. Combine com Log/LUT/scopes, zoom A/B e partes: confira proporção, áudio, duração e ausência dos indicadores no MP4.

Anote configurações, lente, temperatura e falhas. Não conclua ganho de qualidade ou estabilidade diária apenas pelos testes sintéticos. Evidências atuais: [VALIDACAO.md](../validation/VALIDACAO.md).

Referência conceitual consultada em 01/10/2026: [OpenCV — movimento global, modelos rígidos e restrições de inclusão](https://docs.opencv.org/4.13.0/d4/d2c/group__videostab__motion.html). O app não importa a biblioteca OpenCV.

