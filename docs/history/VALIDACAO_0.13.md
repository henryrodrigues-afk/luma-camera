# Validação do Luma Camera 0.13

Data: **01/10/2026**. Versão **0.13.0-stabilization**, código 13. APK de desenvolvimento **arm64-v8a**, Android 10 ou superior. **Resultado visual e uso diário no Galaxy A14 5G ainda não validados.**

## Evidência local

| Verificação | Resultado |
| --- | --- |
| Compilação integrada ARM64 | BUILD SUCCESSFUL em 3 min 2 s |
| JUnit | **334 testes em 42 suítes**; zero falhas, erros ou ignorados |
| Lint | Zero erros; **22 avisos**, mesma quantidade da 0.12 |
| GLSL | **106 contratos aprovados** em WebGL/ANGLE |
| Continuidade | **17 arquivos protegidos idênticos à fonte 0.12**, incluindo gravador, Store, player, relógio, geometria e políticas de Log/máscaras |
| Assets no APK | Os 10 assets próprios correspondem à fonte |
| Fonte compilada | 129 entradas acompanhadas no snapshot da compilação; nenhuma mudou entre o congelamento e o build |
| Assinatura | apksigner aprovado; certificado de desenvolvimento preservado |
| Manifesto/ABI | Código 13, mínimo 29/alvo 34, somente arm64-v8a; sem INTERNET/ACCESS_NETWORK_STATE |
| Android/A14 | **Pendente**; nenhum aparelho conectado no ADB |

Comando integrado: scripts/Verify.ps1 -Abi arm64-v8a. JDK 17, SDK/Build Tools 34, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. Dependências e modelos preservados: Media3 1.4.1, ML Kit 16.0.0-beta6 e MediaPipe Tasks Vision 0.10.14.

Os resultados acima pertencem à compilação anterior à organização das pastas. O ZIP congelado e seus relatórios preservam essa evidência. O projeto organizado tem um snapshot próprio; os wrappers em `scripts/Verify.ps1` e `scripts/VerifyShaders.cjs` mantêm os comandos de verificação para execuções futuras.

Os 32 testes adicionais cobrem estimativa de giros/deslocamentos maiores, escala não rígida, máscara recente/expirada, buffers, concorrência, resultados antigos, margens de rotação, transformações inversas, cadência, perda/retomada, mudanças de ganho e novos modos. Compilações/testes isolados passaram antes da integração; a revisão cruzada de estimador, policy e GPU não encontrou bloqueadores concretos.

Os 10 novos cenários gráficos verificam sinais de rotação, proporção física horizontal/vertical, offsets, orientação/espelho e separação entre LUT/monitores de prévia e encoder. O shader copy usa 15 vetores de uniformes declarados, dentro do mínimo GLES 2 de 16. Usa highp quando disponível e declara fallback mediump. O ensaio WebGL/ANGLE substitui sampler externo por textura 2D: **não valida EGL, SurfaceTexture, samplerExternalOES ou precisão do driver Android**.

## Mudança entregue

O estimador compara até 35 regiões em três escalas e ajusta um movimento rígido de translação/pequena rotação. Consenso distribuído, unicidade, máscara recente e rejeição de escala reduzem referências incoerentes. O cálculo de luminância/movimento roda em CPU com prioridade de fundo, apenas uma tarefa em voo, sem fila de quadros antigos. Readback reduzido continua na GPU.

O filtro causal trata oscilações e movimento sustentado, limita o acúmulo fora da margem e reduz/retoma correção gradualmente na perda comum. Corte real descarta a trajetória anterior. O recorte fica fixo; rotação e deslocamento obedecem às quatro bordas. Toque e retículo usam a transformação e sua inversa, compartilhadas com prévia, MP4 e scopes.

A interface oferece Equilibrado/Câmera parada/Em movimento e uma chave independente para pequenos giros. Desligar giros libera sua reserva para translação. Força/suavidade permanecem ajustáveis durante REC sem reiniciar o filtro. Em 0%, o estado informa somente recorte. Modos e chave são persistidos e entram nos presets. A assistência de fundo também aceita acompanhamento IA sem desfoque.

O [guia de estabilização](../guides/ESTABILIZACAO.md) contém os ajustes e a comparação física. A fonte dos 17 componentes críticos preservados foi comparada byte por byte com o ZIP 0.12. Isso comprova continuidade desses arquivos, sem afirmar que toda a saída codificada permanece idêntica quando a nova correção está ligada.

## Limites e próxima validação

- A correção é local e baseada em imagem; não usa rede neural de estabilização, giroscópio ou EIS/OIS obrigatório.
- Pequenos giros não equivalem a nivelamento do horizonte. Não há reparo de rolling shutter, borrão, perspectiva ou balanço forte de caminhada.
- Recorte preserva proporção/dimensões, mas reduz campo de visão e detalhe disponível. Não acrescenta resolução ou faixa dinâmica.
- Pouca luz/textura, parallax, ambiguidade ou movimento além da busca podem reduzir a correção. Rejeitar esses casos evita aplicar um movimento não confiável.
- GPU readback, cópias e CPU ainda têm custo. Nenhum teste local mede FPS, temperatura, bateria, latência, áudio ou resultado visual no A14.

Compare clipes novos ligados/desligados na mesma cena: celular quase parado, panorâmica com parada/retorno, pequenos giros, alvo em movimento e perda/retomada. Confira ambas as câmeras/orientações, cantos/retículo, áudio, duração e player. Depois combine Log/LUT/scopes, zoom A/B e partes. O [roteiro no A14](TESTE_DIARIO_A14.md) continua com resultados físicos pendentes.

## Artefatos e integridade

- APK: [LumaCamera-0.13-arm64-v8a-debug.apk](../../dist/releases/0.13/apk/LumaCamera-0.13-arm64-v8a-debug.apk) — **64.600.266 bytes**.
- SHA-256 do APK: **84434A84B586B4918E2AA5FCA79F9B489177BC636A54059BA4F0A800DA9EFF91**.
- Certificado SHA-256: **7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2**. Mantém atualização das versões assinadas pela mesma chave de desenvolvimento.
- Projeto congelado da compilação: [LumaCamera-projeto-0.13.zip](../../dist/releases/0.13/source/build-snapshot/LumaCamera-projeto-0.13.zip). Preserva os bytes e a disposição usados na entrega original.
- Projeto com pastas organizadas: [LumaCamera-projeto-0.13-organizado.zip](../../dist/releases/0.13/source/LumaCamera-projeto-0.13-organizado.zip). Inclui fonte, assets/modelos/licenças, Gradle wrapper, scripts e documentação reorganizada; exclui builds, caches, APKs, chaves e local.properties.
- Hashes ficam nos arquivos .sha256 correspondentes. [source-snapshot-0.13.json](../../dist/reports/0.13/source-snapshot-0.13.json) descreve o ZIP congelado; [source-snapshot-organized.json](../../dist/reports/organization/source-snapshot-organized.json) descreve o pacote organizado.

Relatórios da compilação em `dist/reports/0.13/`: build-verification-0.13.log, tests-and-lint-0.13.json, build-inputs-0.13.json, shader-verification.json, apk-assets-0.13.json, apk-identity-0.13.json, apk-signature-0.13.txt, apk-badging-0.13.txt, capture-compatibility-0.13.json, source-snapshot-0.13.json, release-verification-0.13.json e lint-results-debug.html. A reorganização é registrada separadamente em [folder-organization.json](../../dist/reports/organization/folder-organization.json).

Histórico: [validação 0.12](../history/VALIDACAO_0.12.md), [validação 0.11](../history/VALIDACAO_0.11.md). Os recursos profissionais da 0.12 continuam descritos no [guia correspondente](../guides/FERRAMENTAS_PRO_0.12.md). A promoção para uma versão estável depende do roteiro físico.

