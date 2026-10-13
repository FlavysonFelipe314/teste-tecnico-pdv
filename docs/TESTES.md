# Roteiro de validação — H01 a H05

Execute os comandos na pasta `rota-clara-lab`. O ambiente usa somente dados e
integrações fictícias. Java/Maven/Python no host são opcionais quando se usam os
comandos Docker abaixo. Os verificadores conferem `success` e `status` do envelope;
erros de negócio mantêm HTTP 200 por compatibilidade com o kit.

## 1. Subir a versão corrigida

```bash
docker compose up --build -d
docker compose exec -T fiscal-simulado python - < scripts/ready.py
```

Aguarde `LAB READY`. Abra http://localhost:13000 e use Ctrl+F5.

## 2. Validar automaticamente, preservando seus testes manuais

```bash
bash scripts/validate-all.sh
```

Esse comando compila core/Admin, executa os testes em MySQL e valida as cinco
histórias via HTTP em recursos isolados: banco `rota_clara_validation`, core,
Redis, MinIO e provedor fiscal próprios. Reinicia o core e interrompe Redis e
MinIO de teste. Não apaga volumes nem altera pedidos, cache ou cenário fiscal
do laboratório principal. Precisa da porta 18081 livre, Docker Engine Linux
(rede host usada pelos testes Maven em MySQL) e Bash. Os recursos temporários
são removidos ao terminar, inclusive se uma verificação falhar.

O resultado final é `VALIDAÇÃO H01–H05 OK`. Evidências ficam em
`docs/evidencias/h01-h05-integracao.json`, `testes-mysql.log` e `build.log`.
O ensaio integrado usa 3 tentativas e intervalo inicial de 1 segundo para ser
rápido; o ambiente normal usa 5 tentativas e intervalo inicial de 2 segundos.
As regras são as mesmas e a configuração está explícita no script.

Regressão da interface no navegador, sem alterar dados reais:

```bash
bash scripts/test-ui.sh
```

Esse teste usa o Admin compilado com respostas determinísticas da API. Verifica
histórico, campos bloqueados, atualização automática da emissão, protocolo,
reprocessamento só do Gestor, download e controles do cache. As integrações reais
são verificadas separadamente pelo roteiro completo. A primeira execução baixa
Chromium e Playwright dentro de uma imagem de teste, sem instalação no host.

Testes somente do backend, sem Java instalado no host:

```bash
bash scripts/test-replenishment-mysql.sh --docker
```

Com Java 21 e Maven, os testes padrão usam H2:

```bash
mvn -B -f core/pom.xml test
```

Não execute `smoke.py` antes dos exemplos numéricos de base limpa: ele altera
estoques e cria pedido. O smoke continua disponível e aguarda a emissão assíncrona.

## 3. Voltar à base limpa para testar pelo painel

**Apaga todos os pedidos, estoques modificados e documentos locais.** As correções
no código permanecem. Não é necessário resetar a base para atualizar a aplicação.

```bash
docker compose down --volumes
docker compose up --build -d
docker compose exec -T fiscal-simulado python - < scripts/ready.py
```

Resete entre cenários independentes para repetir os valores iniciais. Se estiver
apenas atualizando código, use `docker compose up --build -d core admin`.

## H01 — necessidade e validação pela API

Na base limpa, selecione Gestor:

- Lagoa: café aparece no estoque com saldo 7 e meta 5, mas não na reposição.
- Aurora: arroz tem saldo/meta 10, sem reposição; água tem saldo 4, meta 10, falta 6.

Exemplo de chamada direta proibida (café acima da meta):

```bash
curl -s http://localhost:18080/admin/requests \
  -H 'Authorization: Bearer gestor-local' -H 'Content-Type: application/json' \
  -d '{"destination":"loja-lagoa","items":[{"product_id":"cafe-250","requested":1}]}'
```

Esperado: `success=false,status=400`. Repita com arroz/Aurora, quantidade 1,
e água/Aurora, quantidade 7: ambos são recusados. Confira que nenhum pedido foi
criado e nenhum saldo mudou. A suíte também envia um item válido seguido do
inválido para provar ausência de pedido parcial.

## H02 e H03 — entrega parcial e repetição

Em base limpa, selecione Gestor/Aurora e **Criar solicitação com estes itens**.
Troque para Operador, selecione o pedido, receba **água 4 e café 0** e confirme.

| Produto | Solicitado | Recebido | Falta | Centro | Aurora |
|---|---:|---:|---:|---:|---:|
| Água | 6 | 4 | 2 | 96 | 8 |
| Café | 2 | 0 | 2 | 50 | 3 |

A confirmação e os campos ficam desabilitados. O pedido fica `CONFIRMED` enquanto
o documento pode estar pendente; a emissão não impede o recebimento.

Copie o ID completo exibido em Conferência da entrega:

```bash
PEDIDO_ID='cole-o-id-completo-aqui'
curl -s "http://localhost:18080/admin/requests/$PEDIDO_ID/confirm" \
  -H 'Authorization: Bearer operador-local' -H 'Content-Type: application/json' \
  -d '{"items":[{"product_id":"agua-500","received":4},{"product_id":"cafe-250","received":0}]}'
```

Repetir retorna o mesmo pedido, sem movimentar estoque nem enfileirar outro
documento. Mude água para 3: `success=false,status=409`. Gestor não confirma;
Operador não cria. Recebimento negativo, acima do solicitado e totalmente zerado
é recusado. Concorrência, falta de saldo e rollback são testados automaticamente.

Alternativa automatizada que **altera a base de demonstração** e exige base limpa
com cenário fiscal Sucesso:

```bash
docker compose exec -T fiscal-simulado python - < scripts/h02_h03.py
```

## H04 — provedor indisponível e recuperação

Em base limpa:

1. Como Gestor/Aurora, crie o pedido.
2. Nos controles do laboratório, escolha **Indisponível** e **Aplicar cenário**.
3. Como Operador, receba água 4 e café 0 e confirme.
4. Confira pedido `CONFIRMED`, estoques 96/8 e documento **Aguardando emissão**,
   **Emitindo documento** ou **Aguardando nova tentativa**, com tentativas/motivo.
5. Como Gestor, restaure **Sucesso**. Sem confirmar novamente, aguarde a emissão.
6. Confira **Autorizado · XML disponível** e baixe o XML. Estoque permanece 96/8.

Se a indisponibilidade durar até esgotar as 5 tentativas, aparece **Falha ·
reprocessamento disponível**. Depois de restaurar o provedor, Gestor pode usar
**Tentar emissão novamente**. A chave é preservada; o total de tentativas não
é apagado. Operador não pode reprocessar. Rejeição de negócio não é reprocessada
automaticamente nem oferecida como uma falha temporária.

### Resposta perdida

Em base limpa, escolha **Resposta perdida**, crie e confirme o pedido. O provedor
registra a autorização mas corta a conexão. O core aguarda e consulta a mesma
chave na tentativa seguinte. O documento termina autorizado, com um único
registro no provedor. Estoque não é movimentado novamente.

### Rejeição

Em base limpa, escolha **Rejeição**, crie e confirme. O documento termina
**Rejeitado pelo provedor**, com motivo e uma tentativa. Esperar ou clicar
Atualizar não aumenta as tentativas. O recebimento permanece confirmado.

### Reinício em etapa intermediária

O roteiro automático demonstra um reinício real depois da autorização no
provedor, antes de armazenar o XML. Para reproduzir manualmente, em base limpa:

1. Pare o MinIO: `docker compose stop minio`.
2. Com cenário fiscal Sucesso, crie/confirme o pedido.
3. Aguarde aparecer protocolo do provedor e falha temporária de armazenamento.
4. Pare o core: `docker compose stop core`.
5. Restaure MinIO: `docker compose start minio`.
6. Inicie core: `docker compose start core`.
7. Aguarde o XML; se as tentativas já acabaram, use **Tentar emissão novamente**.

O protocolo e a intenção ficam no MySQL. A retomada consulta a chave existente,
regrava o mesmo objeto e não confirma estoque novamente. A suíte também testa
posse expirada, worker antigo e dois workers concorrentes.

## H05 — cache, separação, atualização e fallback

Em base limpa, como Gestor/Aurora:

1. No diagnóstico do cache, clique **Consultar contadores** e
   **Desativar cache para comparação**.
2. Anote `Consultas de necessidades no banco`. Clique **Atualizar** três vezes;
   consulte os contadores novamente: incremento de 3 consultas de necessidades.
3. Ative o cache. Anote os contadores e repita três Atualizar. Resultado: no máximo
   1 consulta de necessidades e pelo menos 2 acertos (pode já estar aquecido).
4. Troque para Lagoa e volte. Cada loja possui chave própria: Lagoa não sugere café;
   Aurora mantém sua necessidade de café, sem misturar os saldos.
5. Em Aurora, crie/confirme água 4 e café 0. A necessidade de água muda
   imediatamente de 6 para 2, sem esperar o TTL.

**Leituras de versão continuam acontecendo:** uma consulta pequena por GET é o
custo da garantia de atualização e concorrência entre réplicas. A economia
medida é no JOIN de estoque/produtos, não no número total de comandos SQL.
Não se promete ganho de latência para o pequeno volume do laboratório.

Para testar indisponibilidade:

```bash
docker compose stop redis
```

Atualizar continua mostrando dados do banco; `falhas` aumenta. Ainda é possível
criar e confirmar um pedido válido. Após receber, a necessidade deve refletir o
saldo novo, mesmo com Redis desligado. Depois:

```bash
docker compose start redis
```

As consultas voltam a reutilizar cache. A versão do estoque impede reaproveitar
um valor anterior à escrita. Contadores são por processo e reiniciam com o core;
use diferenças antes/depois no mesmo processo. A suíte provoca uma leitura antiga
que termina depois do recebimento e comprova que ela não contamina a chave atual.

## Restaurar dependências após testes manuais

```bash
docker compose start redis minio core
docker compose exec -T fiscal-simulado python - < scripts/ready.py
```

Como Gestor, restaure o cenário fiscal **Sucesso** e ative o cache. Se quiser
repetir todos os exemplos numéricos, use o reset explícito da seção 3.
