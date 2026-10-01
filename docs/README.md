# Documentação do Luma Camera

| Pasta | Conteúdo e ponto de entrada |
| --- | --- |
| `guides/` | Uso das funções: [ferramentas profissionais](guides/FERRAMENTAS_PRO_0.12.md), [foco e desfoque](guides/FOCO_E_DESFOQUE.md), [estabilização](guides/ESTABILIZACAO.md), [Log](guides/LUMALOG.md), [monitores](guides/MONITOR_PRO.md) e [reprodução](guides/REPRODUCAO.md) |
| `development/` | [Arquitetura](development/ARQUITETURA.md), [design](development/DESIGN.md), [duração e gravação](development/DURACAO_E_GRAVACAO.md) e [referências](development/REFERENCIAS.md) |
| `validation/` | [Validação 1.0](validation/VALIDACAO.md), [resultados agregados JSON](validation/RESULTADOS_1.0.json) e [roteiro diário no Galaxy A14 5G](validation/TESTE_DIARIO_A14.md) |
| `history/` | Registros das entregas anteriores: [validação 0.13](history/VALIDACAO_0.13.md), [validação 0.12](history/VALIDACAO_0.12.md) e [auditoria 0.11](history/AUDITORIA_ESTABILIDADE_0.11.md) |

A [validação atual](validation/VALIDACAO.md) registra o build final, **383 testes JVM**, **117 contratos gráficos**, lint e hashes dos APKs. O APK final gravou um novo clipe frontal Log de **25,2 s** no A14, com áudio/timestamps/decodificação aprovados e fim de reprodução interno observado. A cena não tinha pessoa; qualidade visual da IA, microfone externo, temperatura/bateria e percursos completos de uso diário continuam pendentes. O baseline anterior, incluindo três partes, está identificado separadamente. Os registros históricos preservam resultados, hashes e caminhos descritos na época; os artefatos antigos ficam em `dist/archive/releases/<versão>/` e os relatórios preservados em `dist/archive/reports/<versão>/`, conforme disponibilidade. Um nome genérico de relatório em um registro antigo não implica que o relatório atual reproduza aquela versão.

Links históricos para `dist/` identificam artefatos locais da época. Essa pasta não integra o Git/ZIP público; a disponibilidade dos arquivos depende do arquivo local ou das Releases correspondentes. Os registros históricos são preservados sem alterar seus bytes para reescrever esses destinos. Downloads desta entrega ficam nos assets da Release 1.0, com hashes no manifesto externo.

Instalação e comandos do projeto: [README principal](../README.md).
