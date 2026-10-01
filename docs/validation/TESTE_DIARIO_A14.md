# Roteiro de uso diário no A14 — Luma Camera 1.0

Este roteiro amplia os ensaios registrados na [validação 1.0](VALIDACAO.md): o baseline incluiu três partes; o APK final aplicou os novos ajustes e gravou/reproduziu um clipe frontal Log de 25,2 s, com áudio/timestamps/decodificação verificados. Essa cena não tinha pessoa. Qualidade da IA e os percursos completos abaixo continuam pendentes; compilação/JVM/GLSL não os substituem. Atualize por cima para preservar dados, mantendo uma cópia externa do material importante. Use clipes novos para avaliar captura, duração e reprodução atuais.

## Anotar a configuração

Modelo completo/variante e Android: __________
Versão do Luma: __________
Modo/lente, efeitos ativos, Log versão/força, áudio: __________
Espaço/condições térmicas antes e depois: __________
Arquivo/tamanho/duração/resultado: __________

Em App → Recursos do aparelho, consulte o que a lente oferece. Em Gravação → Condições para gravar, atualize a leitura antes/depois dos testes. FPS/bitrate configurados são alvos; não comprovam o valor efetivo do arquivo.

## Percurso básico

1. Com tudo desligado, faça 10 segundos de vídeo com movimento e um som visível (palma). Nas duas câmeras e orientações, confira proporção, topo/baixo, duração, áudio e progresso interno. Frontal espelhada na prévia e não espelhada no arquivo é o comportamento previsto.
2. Assista começo/meio/fim, arraste, ±5s, pausa/rever e saia/volte. Ao concluir, o app deve permitir a tela dormir conforme o sistema. Observe a relação entre palma visível e áudio; medir sincronismo exige análise do arquivo, não apenas impressão visual.
3. Repita sem áudio. O HUD/player devem identificar que o clipe está silencioso, mesmo que a preferência geral do microfone esteja ligada.
4. Tente dois toques rápidos em Gravar/Parar. Não deve surgir um segundo diálogo ou pedido duplicado. Permita/negue o microfone e cancele o diálogo; a interface deve continuar utilizável.
5. Faça 20 clipes curtos. Compare o cronômetro, duração e arquivos. Abra/feche/retome/troque câmera 20 vezes, sem sessão abandonada, gravação inesperada ou travamento.
6. Faça clipes de 2 e 10 minutos nos modos oferecidos. Anote quedas, alerta térmico, bateria, tamanho e reprodução. Repita com cada efeito/monitor separadamente antes de combinar recursos.

## Interrupções e arquivos

| Ação | Comportamento esperado | Resultado físico |
| --- | --- | --- |
| Pausar, girar ou bloquear durante REC | Finalização ao sair da Activity; vídeo salvo ou original preservado com aviso | Pendente |
| Outro app/cliente tomar a câmera | Erro/desconexão compreensível e caminho de reabertura; material não vazio preservado | Pendente |
| Fones/áudio interrompidos durante arraste no player | Soltar a barra respeita a interrupção; retomar exige intenção adequada | Pendente |
| Compartilhar várias vezes e sair da tela | Sem choosers repetidos ou lançamento atrasado | Pendente |
| Recuperar o mesmo original em rotação/reentrada | Uma publicação por origem; cópia concluída validada; sem duplicação por workers concorrentes | Pendente |
| Original incompleto | Rótulo não validado, sem autoplay; compartilhar/inspecionar não significa recuperação confirmada | Pendente |
| Espaço baixo | Início recusado ou finalização pelo watchdog; publicação falha preserva original | Pendente |
| Processo encerrado à força durante REC | Bytes podem ser preservados, mas MP4 sem índice pode ficar inutilizável; não há garantia de reparo | Pendente |

A proteção de espaço usa margens e consulta periódica; outro app pode ocupar espaço abruptamente. Para testar pressão de espaço, use apenas arquivos de teste e não apague material importante. O Luma não grava em segundo plano.

## Ferramentas e layout

- Fonte normal/ampliada, paisagem/retrato e barras por gestos/botões: Gravar/Parar/Galeria/menus acessíveis, sem sobreposição; rolagem preservada ao editar a mesma página.
- Log v1/v2, força e assistência SDR: imagem gravada mantém o perfil escolhido; a LUT correspondente restaura a curva. A assistência é de exibição.
- Pessoas/Objetos: compare **Ajuste automático → Natural/Equilibrado/Forte**, na prévia e no MP4. A intensidade deve mudar sem alterar foco manual/trava, WB, Log ou estabilização; o ajuste desliga o oval. Objetos exigem toque no alvo; mudar só a aparência conserva a seleção válida. Ao perder o recorte, nova seleção deve ser explícita, sem assumir o fundo.
- Atraso/contorno: pessoa andando, mãos/cabelo, dois sujeitos e saída do alvo; máscara atrasada perde efeito e não congela a silhueta. Compare Leve/Qualidade, sem prometer a mesma cadência da gravação. Observe halos em sujeito claro contra fundo escuro e vice-versa, inclusive ao preservar o fundo.
- Exposição: com ISO/obturador/AE travados e ganho ativo, use **Ajustar exposição automaticamente**. Confira retorno à AE, ganho desligado e EV moderado compatível com a lente; Log, WB, foco e outros efeitos devem manter a escolha. Grave cena clara/contraluz e compare zebra, pele e arquivo; não atribua recuperação de clipping ao Log.
- Estabilização: os três tipos de movimento, força/suavidade/recorte, giros ligados/desligados, foco ao toque, panorâmicas e perda/retomada. Pequenos giros têm correção limitada; rolling shutter continua sem correção. Percurso detalhado em [ESTABILIZACAO.md](../guides/ESTABILIZACAO.md).
- Histogramas/zebra/false color/peaking/guias: indicadores ausentes do MP4. Compare FPS/temperatura com cada ferramenta ligada e desligada.

## Se falhar

Registre versão/modo/lente/orientação, passos, duração real e arquivo afetado. No player, salve ⋮ → Salvar diagnóstico de reprodução. Preserve o original e compare um clipe novo após atualizar; não renomeie “não validado” para “recuperado” sem abrir e conferir o arquivo. Os critérios de promoção para estável estão em [AUDITORIA_ESTABILIDADE_0.11.md](../history/AUDITORIA_ESTABILIDADE_0.11.md).

## Recursos profissionais mantidos na 1.0 — percursos completos pendentes

- Busca e atalhos: encontre cada uma das oito funções, fixe/troque três favoritos, teste teclado, rolagem, fonte ampliada e orientação.
- Presets: salve/edite/aplique em lentes diferentes; confira o resumo e formato preservado quando não compatível. Alvo e trava devem exigir nova seleção.
- LUT: importe uma LUT de 17 ou 33 pontos válida e uma inválida; configure entrada SDR/Log; grave com e sem LUT e confirme que só a tela muda. Confira restauração após abrir novamente.
- Scopes: teste individualmente e combinados; confira cores/exposição, leitura do sinal gravado e ausência dos painéis no MP4. Compare aquecimento e cadência sem/com scopes.
- Zoom/foco A/B: execute ambas direções, interrompa por botão/toque/pausa, grave durante movimento. Sem foco manual, A/B físico deve mostrar indisponibilidade.
- Trava + acompanhamento: acompanhe pessoa/objeto com autofoco; trave lente mantendo retículo; libere trava. Teste oclusão, dois sujeitos, fundo sem textura e alvo perdido. Não pode surgir confirmação óptica a partir da máscara.
- Áudio externo: USB/com fio, rota real durante REC, desconexão, Auto, reiniciar app e player com som/palma. Anote dispositivo, Android, falhas e sincronismo.
- Projetos: grave duas tomadas e três partes; confira nomes, filtro da biblioteca, T/P, contador após início falhado e exportação da ficha.
- Partes: limite de 64 MiB, ao menos três partes; assista começo/meio/fim de cada parte, áudio e junções. Repita com sessão longa e pausa/saída. Verifique publicação assíncrona e originais preservados em falha.

Critério: nenhum recurso é promovido a estável apenas por passar os testes JVM/GLSL. Faça os percursos básicos sem recursos e depois ative um por vez antes de combinar.
