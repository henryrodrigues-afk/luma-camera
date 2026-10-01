# Luma Camera 1.0

Câmera Android para criar vídeos com controles profissionais e processamento local, com foco inicial no **Samsung Galaxy A14 5G**. Android 10 ou superior.

[Baixar o APK](https://github.com/henryrodrigues-afk/luma-camera/releases/tag/v1.0.0) · [Guias](docs/README.md) · [Validação](docs/validation/VALIDACAO.md) · [Histórico de mudanças](CHANGELOG.md)

## Começar

1. Instale o APK ARM64 da Release e permita câmera/microfone quando precisar.
2. Grave um clipe curto em 720p/30 e confira áudio, duração e reprodução na biblioteca.
3. Abra **Ferramentas** pela engrenagem. Busque pelo que deseja fazer e use ★ para escolher três atalhos.
4. Ative os recursos individualmente. Resolução/FPS ficam no indicador superior; Galeria abre seus clipes.
5. Para começar com desfoque, use **Foco → Ajuste automático · Pessoas/Objetos → Natural**. Pessoas são recortadas automaticamente; objetos exigem toque dentro do alvo. Em **Exposição**, use **Ajustar exposição automaticamente** para voltar à AE e desligar ganho digital.
6. Para edição, use **Imagem → Preparar imagem para edição** e a LUT LumaLog correspondente à versão/força gravadas.

O APK oficial mantém a identidade de assinatura das versões anteriores para atualizar o app preservando os dados. Chaves e senhas de assinatura ficam fora do repositório. Uma compilação própria usa outra assinatura e não substitui o APK oficial diretamente.

## Ferramentas

| Área | Recursos |
| --- | --- |
| Gravação | Resolução/FPS anunciados pela câmera, bitrate, áudio opcional e partes numeradas |
| Exposição | Ajuste automático com EV moderado, ISO, obturador/ângulo, balanço de branco e travas AE/AWB conforme suporte |
| Imagem | LumaLog v1/v2, ganho, temperatura, contraste, saturação, nitidez e efeitos individuais |
| Foco | Lente automática/manual, trava, foco A/B, acompanhamento por IA e ajustes de desfoque Natural/Equilibrado/Forte para Pessoas/Objetos |
| Zoom | Pontos A/B, duração, direção e interrupção do movimento suave |
| Estabilização | Equilibrado/Câmera parada/Em movimento; força, suavidade, recorte e pequenos giros |
| Monitores | Histograma, waveform, RGB parade, vectorscope, zebra, false color e peaking |
| Prévia | LUT .cube 17³/33³, assistência SDR, guias, área segura e nível |
| Áudio | Entrada automática/externa preferida, rota efetiva e medidor de pico |
| Organização | Presets personalizados, projetos/cenas/tomadas e ficha JSON dos ajustes |
| Biblioteca | Reprodução interna, seek, alternativas de decoder/superfície, compartilhamento e diagnóstico |

[Ferramentas profissionais](docs/guides/FERRAMENTAS_PRO_0.12.md) · [Foco e desfoque](docs/guides/FOCO_E_DESFOQUE.md) · [Estabilização](docs/guides/ESTABILIZACAO.md) · [Log](docs/guides/LUMALOG.md) · [Monitores](docs/guides/MONITOR_PRO.md) · [Reprodução](docs/guides/REPRODUCAO.md)

## Qualidade e compatibilidade

O MP4 usa H.264 SDR de 8 bits e AAC opcional. **LumaLog é simulado sobre o sinal SDR entregue pelo Android**: facilita uma aparência plana para edição, mas não recupera realces cortados nem acrescenta RAW, 10 bits ou faixa dinâmica. LUTs e monitores são somente da prévia.

Desfoque e acompanhamento IA são recursos experimentais, com processamento no aparelho. Pessoas usam ML Kit; objetos usam MagicTouch com seleção por toque. Objetos lisos, pequenos, transparentes, oclusões e movimentos rápidos podem perder o recorte. Selecione novamente quando o alvo for perdido.

A estabilização estima movimento de imagem e usa um recorte fixo; reduz campo de visão/detalhe e não corrige rolling shutter ou borrão. Não exige OIS/EIS ou giroscópio. Controle manual e microfone externo dependem do que o Android e a lente oferecem.

A entrega passou com **383 testes JVM e 117 contratos gráficos**. No A14, o APK final gravou um clipe frontal Log de **25,2 s**, com áudio presente, tempos crescentes, decodificação integral aprovada e fim de reprodução interno observado. A cena não tinha pessoa; qualidade do recorte em cabelos/movimento e uso prolongado continuam pendentes. As evidências estão separadas em [VALIDACAO.md](docs/validation/VALIDACAO.md). Use o [roteiro no A14](docs/validation/TESTE_DIARIO_A14.md) para comparar lentes, orientações, áudio, player e gravações longas. Nenhum teste automatizado garante compatibilidade com todos os drivers.

## Compilar

Abra a raiz do projeto no Android Studio. Use **JDK 17**, SDK/Build Tools **34**, Gradle **8.5**, AGP **8.2.2** e Kotlin **1.9.22**.

Windows:

```powershell
.\scripts\Verify.ps1 -Abi arm64-v8a -PlanOnly
.\scripts\Verify.ps1 -Abi arm64-v8a
node .\scripts\VerifyShaders.cjs
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PlumaAbi=arm64-v8a
```

O build local no Windows usa uma saída sem acentos em `%LOCALAPPDATA%/LumaCamera/verification`. Sem ABI explícita, o APK é universal. A Release oficial é ARM64 para o A14. O wrapper Gradle está incluído; dependências são obtidas de Google Maven/Maven Central.

Para assinar uma compilação release própria, configure **LUMA_SIGNING_STORE**, **LUMA_SIGNING_STORE_PASSWORD**, **LUMA_SIGNING_KEY_ALIAS** e **LUMA_SIGNING_KEY_PASSWORD** no ambiente e execute `:app:assembleRelease`. Sem essas variáveis, o APK release é não assinado. Não publique chaves nem senhas. A 1.0 mantém shrinking desativado para preservar os pontos de entrada JNI dos modelos.

[Guia de scripts](scripts/README.md) · [Arquitetura](docs/development/ARQUITETURA.md)

## Pastas

| Pasta | Conteúdo |
| --- | --- |
| `app/src/main/` | Código Android, recursos, shaders, modelos e licenças |
| `app/src/test/` | Regressões JVM |
| `docs/` | Guias, desenvolvimento, validação e histórico |
| `scripts/` | Verificações e exportadores |
| `gradle/` | Wrapper |
| `.github/workflows/` | Compilação, testes e lint no GitHub |
| `design/archive/` | Conceitos visuais históricos |
| `dist/` | Entregas e relatórios locais por versão; ignorados pelo Git |

APKs e pacotes são distribuídos pelas **Releases**, sem incluir caches, material pessoal ou arquivos de assinatura nos commits.

## Privacidade e licença

Câmera, áudio e análise de imagens funcionam localmente. O manifesto final remove INTERNET e ACCESS_NETWORK_STATE. O armazenamento começa privado e publica em **Filmes/LumaCamera**; erros de finalização/publicação preservam originais não vazios para inspeção/recuperação. Dados privados são apagados ao desinstalar; atualização compatível os mantém.

Código original sob [Apache 2.0](LICENSE). Bibliotecas/modelos mantêm seus [termos e atribuições](THIRD_PARTY_NOTICES.md), incluindo o SDK binário ML Kit. Veja [como contribuir](CONTRIBUTING.md).
