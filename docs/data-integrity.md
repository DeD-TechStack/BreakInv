# Integridade, migração e recuperação local

## Fonte de verdade e unidades

A carteira é local e única. Após reconhecer a abertura de um investimento, `portfolio_movement` é a fonte de verdade para posição, custo e caixa. `LedgerReplay` reconstrói o estado em ordem `(date, id)`; `Position`, `PortfolioState` e `PortfolioMetrics` são projeções, não tabelas concorrentes. As colunas de saldo do cadastro legado não são uma segunda fonte atualizada independentemente.

- Aporte/retirada são fluxos externos; compra/venda transferem valor entre caixa e posição.
- No modo `QUANTITY`, quantidades são inteiras. Preço unitário é `BigDecimal`, persistido como decimal textual. O total é liquidado em centavos inteiros com `HALF_UP`; o preço não é arredondado previamente a dois dígitos.
- Venda baixa custo médio proporcional. Venda de toda a posição baixa todo o custo remanescente. Não há margem, short ou caixa negativo.
- Estornar uma venda exige equivalência exata ao histórico sem ela. Arredondamento de vendas posteriores pode impedir essa equivalência mesmo por um centavo; nesse caso o estorno é recusado e a correção requer reconciliação explícita.
- No modo `VALUE`, compra aumenta custo e resgate reduz o custo declarado; não é inferido ganho realizado apenas porque houve um resgate. Valores/rendimentos estimados precisam ser identificados como estimativa.
- Ajuste exige classificação e motivo. Correção patrimonial não é automaticamente rendimento. Correções de movimentos confirmados preservam o original e o vínculo de estorno; lançamentos não reconstruíveis são recusados. Essa validação também cobre inserções retroativas que invalidariam um estorno posterior e reconciliações intermediárias que já absorveram o movimento em um saldo absoluto. Nesses casos, use reconciliação explícita em vez de inverter efeitos já incorporados.
- A chave de intenção identifica uma submissão. Mesmo conteúdo e chave retornam o registro existente; conteúdo diferente para a mesma chave é rejeitado. A validação e os efeitos do replay pertencem à mesma transação SQLite.
- Cadastro com histórico não pode ser apagado: arquivamento preserva os vínculos. Arquivar não representa venda ou retirada.

Não são implementados tributação, lotes fiscais, quantidades fracionárias, multicorretora ou sincronização bancária. O produto registra informações; não envia ordens financeiras.

## Legado e ausência de informação

As tabelas antigas `transactions`, `flows` e snapshots são preservadas. Elas não são reproduzidas cegamente para formar os saldos novos: compras/vendas históricas podem não ter atualizado corretamente caixa e posição. A abertura é um marco explícito, não uma compra inventada.

`proposeOpening`/`proposeCashOpening` propõem reconhecimento e sinalizam incerteza. Valores `REAL` não são regravados em massa. Conversão necessária ao reconhecimento usa decimal/`HALF_UP`; dados ausentes ou contraditórios exigem confirmação/reconciliação, não inferência de operações faltantes. Não se deve selecionar uma data anterior e usar informação posterior como se fosse cotação histórica.

Confirmar uma abertura sinalizada preserva sua pendência e a nota de origem. Os movimentos ficam bloqueados até a reconciliação explícita, com motivo. Reconhecer ou reconciliar o caixa também atualiza o snapshot do dia.

Em uma base sem caixa legado, o primeiro movimento cria um marco técnico de caixa zero em 01/01/1900, início do intervalo de datas suportado. Esse marco não representa um saldo histórico descoberto nem um aporte: permite validar movimentos retroativos sem limitar artificialmente a data ao primeiro lançamento digitado. Marco e movimento são uma única transação; uma operação rejeitada não deixa a abertura gravada. Aberturas de saldos legados declarados continuam limitando a reconstrução à sua data efetiva.

## Migrações

`SchemaMigrator` registra versões em `schema_migrations` e executa cada versão em transação:

1. Baseline e complementação de colunas identificadas do cadastro legado.
2. Modo da posição, data de arquivamento e observação.
3. Auditoria de órfãos, ledger, proteção de exclusão e tabelas de planos/cenários.

Cada conexão criada por `Database` habilita FK e timeout de bloqueio. Operações de escrita usam conexão independente e transação; uma conexão compartilhada de leitura não deve ser reutilizada como transação do serviço.

Uma falha na versão atual reverte essa versão. Versões anteriores já concluídas podem continuar registradas: o conjunto inteiro não é prometido como uma única transação. Órfãos provocam interrupção com diagnóstico, sem apagamento automático. Não desligue FK nem altere `schema_migrations` para contornar um erro. Uma base de versão futura é recusada; restaurar requer também um executável compatível.

Antes de uma migração que altere uma base existente com dados, o aplicativo cria e confere uma cópia de segurança do tipo `pre-migracao` (veja a seção seguinte). Se a cópia falhar, a migração não roda e a base fica como estava. Base nova ou vazia não gera cópia. Não se recomenda abrir o banco migrado com uma versão antiga do aplicativo.

## Cópias de segurança no aplicativo

Em Configurações → Dados e sobre o aplicativo → Cópias de segurança:

- "Criar cópia de segurança" grava a cópia por `VACUUM INTO` num arquivo temporário (`.partial`). Antes de publicá-la com um nome novo, o aplicativo confere, numa conexão nova e somente leitura:
  - integridade (`integrity_check`);
  - versão dos dados;
  - mesmo conteúdo da origem: impressão digital de todas as linhas e do esquema.

  Chaves estrangeiras e reprodução do livro também são conferidas; uma falha nelas aparece como aviso. Se outra conexão gravar durante a cópia, ela é recusada. Sem mudança desde a última cópia, nenhuma cópia nova é feita.
- Cópias automáticas:
  - uma na primeira abertura do dia, se os dados mudaram;
  - antes de uma migração;
  - antes de restaurar.
- A pasta gerenciada é `${user.home}/BreakInv/backups`, no mesmo disco dos dados: ela não protege contra falha do disco. "Salvar cópia em outro local…" grava uma cópia conferida numa pasta escolhida, que o aplicativo nunca apaga.
- O registro `breakinv-copias.json` lista as cópias da pasta gerenciada, sem token e sem valores. A retenção só apaga cópias automáticas listadas nele, com o nome no padrão do recurso. Por padrão, mantém as 10 automáticas mais recentes, num limite de 1 GB.
- A retenção nunca apaga:
  - a cópia válida mais recente;
  - a mais recente sem avisos, porque a restauração recusa cópias com avisos;
  - a pré-migração mais recente;
  - a pré-restauração mais recente, que é o caminho de volta da última restauração;
  - as cópias manuais;
  - nenhum arquivo que não esteja no registro.

  Acima do limite, as automáticas mais antigas saem primeiro, e o resultado diz quantas foram apagadas. Se só as protegidas já passam do limite, nenhuma cópia é apagada para caber, e a tela mostra o conflito.

  Uma cópia que não pode ser apagada (somente leitura ou aberta em outro programa) gera um aviso e continua no registro; a cópia nova vale do mesmo jeito. As cópias feitas durante uma restauração não rodam a retenção: ela fica para a próxima cópia.

  Um registro ilegível é guardado com outro nome e nada antigo é apagado. O aviso continua na tela enquanto o aplicativo estiver aberto. A limpeza de temporários interrompidos só roda quando nenhuma outra janela do BreakInv está aberta.
- "Restaurar…":
  1. copia o arquivo escolhido para um temporário, sem alterá-lo, e o confere;
  2. recusa arquivo que não seja do BreakInv, versão mais nova, dano, referências inconsistentes, livro que não se reproduz e arquivo com `-journal` ou `-wal` ao lado;
  3. mostra uma prévia e pede confirmação; numa cópia anterior ao livro de movimentações, a prévia conta as tabelas do formato anterior;
  4. só continua se nenhuma outra janela do BreakInv estiver aberta (`breakinv-instancia.lock`, ao lado do banco). Uma janela aberta nesse intervalo espera o fim da restauração para abrir;
  5. guarda uma cópia conferida dos dados atuais;
  6. confirma que os dados não estão em uso e que nada foi gravado depois dessa cópia. Se algo foi gravado, recusa e pede para tentar de novo;
  7. faz a troca e confere o banco contra a impressão digital do arquivo escolhido;
  8. se algo falhar, volta para a cópia dos dados atuais.

  Uma cópia de versão anterior é migrada normalmente. Enquanto a troca acontece, o resto da janela fica bloqueado e o BreakInv não fecha.
- Dados atuais danificados: se a cópia conferida dos dados atuais falhar porque eles estão danificados (`integrity_check`, que também acha entradas de índice que não batem com as linhas), o aplicativo pergunta de novo. Com a confirmação, o arquivo atual é guardado como está, byte a byte, como `breakinv-danificado-<data>-<sufixo>.db` na pasta das cópias. Esse arquivo não entra no registro nem na retenção, e o aplicativo nunca o apaga. Só então a cópia escolhida substitui os dados. Se a troca falhar, as páginas danificadas voltam e são comparadas com o arquivo guardado. Dados sem dano nunca seguem por esse caminho. Se o arquivo atual nem abre para a troca (cabeçalho danificado, por exemplo), a restauração dentro do aplicativo não é possível: a mensagem diz o motivo, nada é alterado e o caminho é a restauração deliberada abaixo, com o aplicativo fechado, levando junto o `-journal`, o `-wal` e o `-shm` que estiverem ao lado do arquivo. "Em uso" só aparece quando outro acesso segura o arquivo naquele momento.
- As cópias contêm todos os dados, inclusive o token BRAPI, sem criptografia. Elas não incluem as preferências de Java Preferences.

O procedimento manual abaixo continua válido como alternativa, com o aplicativo fechado.

## Backup antes de uma atualização

O banco fica em `${user.home}/BreakInv/breakinv.db`; no Windows normal, geralmente `%USERPROFILE%\BreakInv\breakinv.db`. Verifique o `user.home` efetivo se ele foi substituído por opção da JVM.

1. Feche todas as instâncias do BreakInv e confirme que não há processo da aplicação escrevendo na base. Não mate indiscriminadamente todos os processos Java.
2. Faça cópia integral da pasta `BreakInv` para uma pasta nova, identificada por data, fora do diretório de origem. Não sobrescreva backup anterior. Inclua eventuais arquivos `-wal`, `-shm` ou journal; não os exclua manualmente para “limpar”. Uma cópia feita durante escrita não é backup consistente.
3. Registre versão do aplicativo/schema e hash SHA-256 de cada arquivo copiado; compare origem e destino enquanto a aplicação permanece fechada.
4. Valide uma cópia de ensaio do backup com um leitor SQLite confiável: `PRAGMA integrity_check` deve devolver apenas `ok`; `PRAGMA foreign_key_check` não deve listar violações. Não corrija dados automaticamente se falhar. Verifique ainda IDs, contagens, saldo e histórico conhecidos; integridade estrutural não prova correção financeira.
5. Preserve uma cópia intocada. Só faça a migração de ensaio em outra cópia, com home e preferências isolados. Atualizar o banco pessoal exige decisão específica do proprietário.

Exemplo de cópia no PowerShell, somente após fechar a aplicação e conferir os caminhos:

```powershell
$source = Join-Path $env:USERPROFILE 'BreakInv'
$backup = Join-Path $env:USERPROFILE ('BreakInv-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (!(Test-Path -LiteralPath $source -PathType Container)) { throw 'Origem ausente' }
if (Test-Path -LiteralPath $backup) { throw 'Destino já existe' }
Copy-Item -LiteralPath $source -Destination $backup -Recurse -ErrorAction Stop
(Get-FileHash -LiteralPath (Join-Path $source 'breakinv.db') -Algorithm SHA256).Hash
(Get-FileHash -LiteralPath (Join-Path $backup 'breakinv.db') -Algorithm SHA256).Hash
```

Os dois hashes do exemplo devem coincidir, mas também confira todos os demais arquivos da pasta, se houver. Não abra a aplicação durante a comparação.

## Restauração deliberada

Para uma cópia feita pelo aplicativo (um arquivo `breakinv-<tipo>-<data>-<sufixo>.db` na pasta das cópias), o caminho manual é mais curto: com o BreakInv fechado, mova o `breakinv.db` atual, e os `breakinv.db-journal`, `breakinv.db-wal` e `breakinv.db-shm` que houver ao lado dele, para outra pasta; depois copie o arquivo da cópia para o lugar dele, com o nome `breakinv.db`. Os arquivos `breakinv-danificado-*` guardam dados danificados e não servem para restaurar. A mensagem do aplicativo mostra os dois caminhos completos. Os passos abaixo valem para o backup manual de pasta inteira.

1. Feche o BreakInv. Escolha um backup previamente validado e a versão compatível do executável.
2. Mova a pasta atual para outra pasta de quarentena, com nome novo, em vez de sobrescrever ou apagar. Preserve os arquivos auxiliares junto do banco correspondente; não misture WAL de uma versão com DB de outra.
3. Copie a pasta completa do backup para o caminho original, que deve estar ausente. Não mova nem modifique o backup intocado.
4. Confira hashes e valide uma cópia de ensaio. Abra primeiro em ambiente sintético/isolado e compare saldos, IDs, vínculos, plano selecionado e cenários.
5. Só então autorize a abertura do banco restaurado no aplicativo compatível. Se o diagnóstico persistir, mantenha quarentena e backup, não tente SQL destrutivo ou desativação de integridade.

O token BRAPI pode estar em `app_settings`; trate toda cópia como sensível. O procedimento SQLite não inclui as preferências de boas-vindas de Java Preferences. Não exporte o registro inteiro do Windows para complementar um backup.

## Histórico, snapshots e métricas

Snapshots representam o estado atualizado do dia, não um fechamento financeiro garantido. Repetição atualiza cada posição aplicável, inclusive posição encerrada com zero explícito. Cotação atual não reconstrói snapshot passado ausente. `CURRENT_ESTIMATE` e a indicação de valoração ao custo identificam limites de qualidade.

Patrimônio, custo, fluxo externo líquido, ganho realizado e não realizado são conceitos separados. Aporte não é lucro. Variação do patrimônio não é TWR/XIRR. CDI/IPCA/SELIC atuais anualizados e taxas cadastradas não equivalem a uma série histórica realizada; comparações devem expor essa limitação.

Planos de alocação e cenários são dados analíticos separados: salvar/excluir um plano ou uma simulação não gera movimento nem altera a carteira. Pesos usam pontos-base (10.000 = 100%); cenários guardam parâmetros/premissas e versão do cálculo, não dependem de uma atualização silenciosa de taxas para reproduzir um resultado antigo.

Salvar um plano pelo editor persiste cabeçalho, itens e seleção em uma única transação. Falhas não deixam um plano parcialmente salvo ou uma seleção nova. Durante gravações de cenários, a ação Novo e a troca de cenário ficam bloqueadas; callbacks de consultas antigas não devem religar um formulário substituído.

Planos/cenários inválidos são identificados individualmente por aviso, sem apagamento ou reparo silencioso. Os demais continuam disponíveis. Se o plano selecionado estiver inválido, a análise usa ARCA com aviso e preserva a seleção armazenada para correção posterior. Uma falha geral de banco não é apresentada como lista vazia.

O JSON de cenários exige inteiros exatos, campos obrigatórios e versão consistente com a coluna; valores fracionários, fora da faixa ou de tipo incompatível não são truncados nem convertidos silenciosamente. A leitura continua compatível com o formato gravado pelo próprio aplicativo.

Durante salvamento, os formulários bloqueiam alterações de parâmetros, tipo e consultas de mercado. Respostas de consultas iniciadas antes da gravação não alteram o conjunto de premissas capturado. Diálogos de movimento e reconhecimento recusam fechamento durante a escrita e atualizam a tela no caminho de sucesso. Isso não constitui garantia de recuperação de uma operação interrompida por encerramento forçado do processo.
