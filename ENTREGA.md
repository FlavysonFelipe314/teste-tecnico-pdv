# Rota Clara — implementação H01 a H05

Entrega incremental em código-fonte. As cinco histórias estão implementadas no
core existente e no Admin, com testes, evidências e roteiro reproduzível para avaliação.

## Execução e organização

```bash
docker compose up --build -d
docker compose exec -T fiscal-simulado python - < scripts/ready.py
bash scripts/validate-all.sh
```

O último comando valida em recursos isolados e preserva os dados do laboratório
principal. Instruções completas e testes manuais: [docs/TESTES.md](docs/TESTES.md).
Não é necessário instalar Java/Maven/Python no host para esse caminho Docker.

- `core/.../replenishment`: necessidade, confirmação transacional e cache.
- `core/.../documents`: fila persistente, worker e integrações externas.
- `V2__durable_documents_and_inventory_versions.sql`: evolução de bases existentes.
- `core/src/test`: regressões, concorrência, atomicidade, recuperação e cache.
- `scripts/validate-all.sh`: build, suíte MySQL e testes HTTP das cinco histórias.
- `docs/evidencias`: resultados executados e captura do painel.

Os contratos originais em snake_case e o envelope `{success,status,data,message}`
foram preservados, inclusive HTTP 200 com erro de negócio no envelope. XML é
binário e exige identidade local autorizada. A V1 original não foi alterada.

## H01 — sugestão de reposição

**Sintoma → causa:** o café excedente da Lagoa aparecia com necessidade 2 porque
`Math.abs(meta - saldo)` transformava excedente em falta.

**Decisão → alteração:** cálculo compartilhado `max(0, meta - saldo)` no backend.
A consulta lista apenas necessidades positivas. A criação valida todos os itens
antes de gravar, sempre lendo o banco, sem usar cache para autorizar quantidades.

**Evidência:** saldo abaixo (água 4/10 → falta 6), igual (arroz 10/10 → 0), acima
(café 7/5 → 0), chamada direta sem necessidade e quantidade acima da falta.
Pedidos mistos inválidos não deixam pedido parcial nem alteram estoque.

**Limite:** pedidos em aberto não reservam nem reduzem a necessidade; duas
solicitações diferentes podem competir pelo mesmo saldo disponível no centro.
A confirmação verifica o saldo sob bloqueio antes de qualquer movimentação.

## H02 — recebimento único

**Sintoma → causa:** confirmar repetidamente movimentava estoque e processava
documentos de novo, sem verificar estado nem bloquear o pedido.

**Decisão → alteração:** `SELECT ... FOR UPDATE` no pedido é a primeira leitura da
transação. Repetição com os mesmos produtos/quantidades retorna o resultado salvo,
independentemente da ordem do JSON. Repetição divergente retorna conflito
`success=false,status=409`. A confirmação concorrente aguarda o commit da primeira.

Todos os itens são validados antes de escrever; os saldos do centro são bloqueados
em ordem estável. Estoques, recebido, estado do pedido, versões de estoque e
intenção documental pertencem à mesma transação. Falha em qualquer gravação
faz rollback do conjunto. Gestor cria, Operador confirma; a autorização também
é aplicada em repetições. O bloqueio no MySQL funciona entre réplicas do core.

**Evidência:** repetição sequencial, duas chamadas idênticas simultâneas, duas
divergentes, saldo insuficiente no segundo item, falha após a primeira escrita e
papéis. Água permanece centro 96/Aurora 8; há uma única intenção documental.

**Limite:** movimentações duplicadas anteriores não são revertidas automaticamente.

## H03 — histórico da entrega parcial

**Sintoma → causa:** a confirmação sobrescrevia `requested` com `received`, e o
faltante ficava zero mesmo em entrega parcial.

**Alteração:** UPDATE somente de `received`; `requested` permanece imutável.
`missing = requested - COALESCE(received,0)` é retornado pela API e exibido no Admin.
Campos e botão ficam desabilitados no pedido confirmado. O backend permanece
protegido contra chamadas diretas. Negativo, acima do solicitado e recebimento
inteiramente zerado são recusados; um item zerado é permitido com outro positivo.

| Produto após confirmar água 4/café 0 | Solicitado | Recebido | Faltante | Centro | Aurora |
|---|---:|---:|---:|---:|---:|
| Água | 6 | 4 | 2 | 96 | 8 |
| Café | 2 | 0 | 2 | 50 | 3 |

**Evidência:** teste da entrega parcial, GET do detalhe e
[captura do painel](docs/evidencias/h03-painel.png). A captura usa o Admin real com
API e banco isolados; asserções no navegador verificaram 6/4/2 e edição desabilitada.

**Histórico perdido:** o banco antigo não permite distinguir uma entrega integral
legítima de um solicitado já sobrescrito. Não se inventam valores a partir de
metas/saldos atuais. Preserve os registros e reconcilie somente com fonte
confiável (backup, pedido original ou registro independente), com auditoria da
correção. Sem fonte, documente a informação perdida. H03 não precisou de migração;
a nova migração é para H04/H05.

## H04 — emissão recuperável

**Sintoma → causa:** a confirmação chamava provedor e MinIO sincronamente, gravava
FAILED sem recuperação e misturava disponibilidade externa com o recebimento.

**Decisão:** usar `documents` como fila durável/outbox no MySQL e um worker dentro
do core. Uma linha por pedido, com chave única `doc-{id}`, preserva a intenção
junto com a confirmação; não há janela entre commit e publicação em uma fila.
RabbitMQ permanece disponível no kit, mas não é necessário para essa implementação.

**Alteração:** a confirmação grava `PENDING`, payload imutável, chave e próxima
execução na mesma transação dos estoques. Não chama rede. O worker captura uma
tarefa em transação curta com `FOR UPDATE SKIP LOCKED`, incrementa tentativas e
grava token/prazo de posse. Chamadas externas ocorrem fora dessa transação.

```text
PENDING → PROCESSING → AUTHORIZED_SIMULATION (somente depois de persistir XML)
                    → REJECTED_SIMULATION (motivo de negócio, terminal)
                    → RETRY_WAIT → PROCESSING
                    → FAILED (limite ou falha permanente)
FAILED/RETRY_WAIT → PENDING (reprocessamento autorizado pelo Gestor)
PROCESSING abandonado → retomada após vencer a posse
```

Cada tentativa primeiro consulta o provedor pela mesma chave. Resposta perdida,
reinício após autorização e falha no MinIO são reconciliados com o documento já
registrado. O protocolo é preservado antes de armazenar XML. O objeto usa chave
fixa `doc-{id}.xml`; repetir upload tem o mesmo destino. Atualizações da fila são
condicionadas ao token, impedindo que um worker antigo sobrescreva outro.

Configuração padrão: 5 tentativas por ciclo, intervalo crescente 2/4/8/16 segundos
(limitado a 60), posse de 30 segundos, consulta à fila a cada segundo. Tentativas
totais são preservadas no reprocessamento; apenas o orçamento do ciclo reinicia.
Rejeição não entra em loop nem reprocessamento automático. Tarefa com payload
inválido fica FAILED, sem bloquear indefinidamente as demais.

Admin mostra estado físico separado do documental, motivo, tentativas, próxima
execução e protocolo, com atualização automática a cada 2 segundos enquanto há
trabalho. Gestor pode agendar reprocessamento de FAILED/RETRY_WAIT pelo painel ou
`POST /admin/requests/{id}/document/reprocess`; Operador não pode. O XML autorizado
fica privado no MinIO e é recuperado por API autenticada. Indisponibilidade de
MinIO não impede a inicialização nem a confirmação física.

**Migração:** V2 acrescenta os campos de fila e índice, retoma FAILED/PROCESSING
legados como PENDING e cria intenção para confirmados sem documento. Autorizados
e rejeitados são preservados. Para tarefas antigas sem snapshot, usa-se apenas o
recebido já registrado; não se reconstrói a quantidade solicitada perdida.

**Evidência:** indisponibilidade, limite, reprocessamento, rejeição, resposta
perdida, falha no MinIO, posse expirada, final abandonado, token antigo, dois
workers e migração V1→V2. No ensaio HTTP real o core foi parado depois da
autorização no provedor, antes de armazenar XML, e iniciado novamente. Mesmo
protocolo, mesmo documento, mesmo estoque. API sem identidade recebe status 401
no envelope; acesso direto sem credenciais ao objeto MinIO recebe HTTP 403.

**Limites:** a fila possui entrega ao menos uma vez; efeitos externos únicos
dependem da idempotência por chave do provedor e do objeto fixo. Não há promessa
de transação distribuída. Retomada de PROCESSING pode esperar a posse vencer.
Frequência, lote e tempos são dimensionados para o laboratório; produção exigiria
monitoramento da fila, retenção, ajuste de capacidade e política operacional de
reprocessamento. Nenhum novo serviço de negócio foi necessário.

## H05 — cache correto

**Sintoma → causa:** cada consulta repetia o JOIN de estoque/produtos no MySQL.

**Decisão → alteração:** Redis armazena somente a lista de necessidades, sem
identidade, token ou pedidos. Chave:

```text
{namespace}:{location_id}:{version}
namespace padrão = rota-clara:needs:v1
TTL = 60 segundos
```

Uma tabela `inventory_versions` registra versão por localização. A confirmação
incrementa as versões do centro e destino na mesma transação do estoque.
Repetição ou rollback não incrementa. Cada GET lê a versão comprometida no banco
e acessa a chave correspondente. A versão nova torna a antiga inacessível
imediatamente; TTL apenas remove entradas antigas. Não há janela dependente de
uma chamada de invalidação ao Redis depois do commit.

Uma leitura antiga que publica depois da escrita só preenche a chave antiga.
Se Redis cair durante a escrita ou leitura, o banco continua sendo a fonte;
a volta do Redis não reativa a chave anterior. O protocolo funciona entre
réplicas que compartilhem banco e namespace. Ambientes/bancos diferentes devem
usar namespaces diferentes (`LAB_NEEDS_NAMESPACE`). Se forem introduzidos filtros,
eles precisam entrar na chave e no contrato de versão.

**Custo explícito:** permanece uma leitura pequena de versão por GET. Reduzimos
as consultas do JOIN; não afirmamos redução do número total de comandos SQL nem
ganho de latência. O teste compara 3 consultas de necessidades sem cache com no
máximo 1 quando ativo, além de acertos e leituras de versão. Resultado com cache
já aquecido pode ser 0 JOINs para 3 chamadas.

**Visibilidade:** contadores por processo em `GET /admin/lab/cache-metrics` e no
painel do Gestor: hits, misses, errors, needs_db_queries e version_db_queries.
`PUT /admin/lab/cache-enabled` permite comparação controlada no laboratório.
Redis tem timeouts de 250 ms; falha/corrupção faz fallback ao banco. Redis/RabbitMQ
não bloqueiam a saúde do core; não são necessários para confirmar recebimento.

**Evidência:** reutilização, chaves por loja, lista vazia, TTL, mudança imediata,
fallback, recuperação com chave velha, falha de escrita, cache inválido, validação
fresca na criação e leitura antiga terminando depois da confirmação. O ensaio HTTP
desliga e liga um Redis real e confirma recebimento durante a indisponibilidade.

**Limites:** contadores são por réplica e reiniciam com o processo. Consistência é
para consultas iniciadas depois do commit do recebimento; uma consulta que já
estava em andamento pode representar o estado anterior. Mudanças diretas em
inventory fora da aplicação precisam incrementar inventory_versions na mesma
transação. Chaves antigas permanecem até TTL, ocupando espaço temporário.

## Resultados executados e uso de IA

- 56 testes em H2 e os mesmos 56 em MySQL 8.4.5: zero falhas/erros.
- Build Docker do core com `mvn verify` e Admin com TypeScript/Vite.
- Regressão da interface no Chromium: histórico, bloqueio, polling, protocolo,
  reprocessamento, download e contadores; usa API determinística, separada das integrações reais.
- Ensaio HTTP H01–H05 com MySQL, Redis, fiscal e MinIO reais, incluindo reinício.
- [Resultado integrado](docs/evidencias/h01-h05-integracao.json).
- [Resumo da suíte](docs/evidencias/h01-h05-testes.txt).
- [Resultado da interface](docs/evidencias/h04-h05-interface.txt).
- [Roteiro completo](docs/TESTES.md).

A revisão final também verifica snapshots documentais corrompidos e entradas de
cache nulas/incompletas. Os validadores limitam memória, e o teste da interface
tem prazo máximo de 90 segundos. As imagens de teste são obtidas da configuração
atual, evitando depender de referências antigas de containers após reinícios.

Codex foi usado na investigação, implementação, testes e documentação. A
verificação incluiu transações/bloqueios no MySQL, falhas de dependências reais e
compilação e regressão da interface. A atualização operacional aplicou V2
via Flyway sem mudar os estoques existentes. Documentos e dados são sintéticos, sem validade fiscal.
