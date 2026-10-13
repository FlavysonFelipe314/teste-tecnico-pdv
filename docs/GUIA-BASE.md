> Guia da base original, anterior às correções. Para a versão entregue, consulte
> [ENTREGA.md](../ENTREGA.md) e [TESTES.md](TESTES.md).

# Guia técnico da base

O enunciado com as cinco histórias, critérios de aceitação e entregas está em [Desafio técnico](DESAFIO.md).

Este arquivo descreve o que existe, sem prescrever a solução dos chamados.

## Execução e manutenção

Todos os comandos abaixo são executados em `rota-clara-lab/`.

```sh
docker compose up --build -d
docker compose exec -T fiscal-simulado python - < scripts/ready.py
docker compose ps
docker compose logs --tail 100 core
# Recompilar após mudar a API; o Dockerfile também executa os testes Maven:
docker compose up --build -d core
# Recompilar após mudar a interface; inclui TypeScript e build Vite:
docker compose up --build -d admin
# Parar preservando banco, objetos e estado do provedor:
docker compose down
# Reiniciar com os dados persistidos:
docker compose up -d
```

Para voltar à população inicial, `docker compose down -v` **apaga todos os volumes deste laboratório**. Depois execute `docker compose up -d` e o verificador de prontidão. Não use esse comando para preservar evidências. O nome do projeto é `rota-clara-lab`; mantenha um único laboratório ativo nos endereços definidos. Para duas cópias simultâneas, configure nomes e portas separados.

MySQL, RabbitMQ, MinIO e o provedor documental usam volumes. Redis é cache descartável e não persiste ao recriar o container. Os arquivos do host não são montados nos serviços de negócio: alterações exigem novo build. Não há código pronto de worker documental ou implementação de cache. A inicialização do core cria o bucket privado `rota-clara-documentos` e aplica a migração Flyway apenas quando necessário.

## Stack e organização

- API: Java 21, Spring Boot 3.5.7, Spring Cloud 2025.0.0, JdbcTemplate, MySQL e Flyway; testes JUnit.
- Admin: React 18.3.1, TypeScript 5.7.3, Vite 6.4.4; Nginx faz proxy `/admin` para o core.
- Infra local: MySQL 8.4.5, Redis 7.4.3, RabbitMQ 4.1.0, Eureka e MinIO.
- Provedor: Python 3.12 e SQLite; simulação controlada, não integração fiscal oficial.

`common` concentra envelope, erros e identidade sintética; `admin` expõe rotas; `replenishment` contém consultas e transações; `documents` acessa o provedor e o armazenamento. O core registra-se como `rota-clara-core` no Eureka. As chamadas ao provedor e ao MinIO usam URLs fixadas no ambiente, sem resolução por Eureka nesta base.

O cliente S3 usa região `us-east-1`, endpoint local e path-style. Essa nomenclatura do protocolo não implica acesso à AWS. Bucket e objetos não têm política pública. O provedor local possui endpoints de controle intencionalmente abertos na rede de laboratório; não são endpoints para publicação em produção.

Imagens base e serviços de infraestrutura estão fixados por digest; dependências do Admin usam lockfile e a API usa versões/BOMs Maven. O primeiro build continua exigindo os repositórios públicos dessas dependências.

## Contratos atuais

Identidade: `Authorization: Bearer gestor-local` (MANAGER), `Bearer operador-local` (OPERATOR). Tokens fixos não representam autenticação de produção. Leitura exige identidade válida; criação exige MANAGER; confirmação exige OPERATOR. A autorização é aplicada no backend.

| Método e rota | Entrada / saída |
|---|---|
| GET `/admin/session` | Perfil da identidade |
| GET `/admin/inventory` | Saldos e metas por local/produto |
| GET `/admin/needs?store=loja-aurora` | Produtos com necessidade positiva |
| POST `/admin/requests` | `{destination, items:[{product_id,requested}]}` |
| GET `/admin/requests` | Lista de solicitações e estado documental |
| GET `/admin/requests/{id}` | Cabeçalho e itens: requested, received, missing |
| POST `/admin/requests/{id}/confirm` | `{items:[{product_id,received}]}` para todos os itens |
| GET `/admin/requests/{id}/document` | Estado, chave, protocolo, motivo e tentativas |
| GET `/admin/requests/{id}/document/xml` | XML do MinIO via API autenticada |
| PUT `/admin/lab/fiscal-mode` | `{mode}`; apenas MANAGER |

Respostas de negócio usam `{success,status,data,message}`. Confira `success` e o `status` semântico, não só HTTP. XML retorna bytes com `Content-Type: application/xml`; erro usa o envelope. A migração contém os IDs `centro-horizonte`, `loja-aurora`, `loja-lagoa`, `agua-500`, `cafe-250` e `arroz-1000`.

Exemplo de solicitação (base limpa):

```sh
curl -s http://localhost:18080/admin/requests \
  -H 'Authorization: Bearer gestor-local' \
  -H 'Content-Type: application/json' \
  -d '{"destination":"loja-aurora","items":[{"product_id":"agua-500","requested":6}]}'
```

Use o `id` retornado na confirmação. O Admin permite fazer a mesma sequência sem usar terminal.

## Provedor FiscoLab 27

O Admin possui controles de cenário fiscal. Também é possível configurá-lo diretamente:

```sh
curl -s -X PUT http://localhost:18090/admin/mode \
  -H 'Content-Type: application/json' -d '{"mode":"indisponivel"}'
# Recuperar disponibilidade:
curl -s -X PUT http://localhost:18090/admin/mode \
  -H 'Content-Type: application/json' -d '{"mode":"sucesso"}'
```

| Modo | Comportamento de uma nova chave |
|---|---|
| `sucesso` | Registra autorização simulada e XML |
| `rejeicao` | Registra rejeição definitiva com motivo, sem XML |
| `indisponivel` | Responde 503 antes de processar |
| `resposta-perdida` | Persiste autorização e encerra a conexão antes da resposta |

`GET /admin/mode` mostra o modo atual. O modo é global e persistido: altere-o de forma controlada durante a demonstração. Para testar outro cenário, crie nova solicitação/chave. Uma chave já processada retorna o resultado persistido, independentemente do modo atual.

`POST /documents` recebe:

```json
{
  "document_key":"doc-ID-DA-SOLICITACAO",
  "delivery_id":"ID-DA-SOLICITACAO",
  "origin_id":"centro-horizonte",
  "destination_id":"loja-aurora",
  "items":[{"product_id":"agua-500","quantity":4}]
}
```

O corpo inclui `status` (`AUTHORIZED_SIMULATION` ou `REJECTED_SIMULATION`), chave, protocolo ou motivo. Mesma chave e mesmo payload retornam o resultado existente; mesma chave com payload diferente retorna HTTP 409. `GET /documents/{document_key}` consulta o estado persistido e `GET /documents/{document_key}/xml` recupera o XML autorizado. O core usa `doc-` seguido do ID da solicitação.

A simulação permite demonstrar reconciliação após resposta perdida sem chamar uma autoridade fiscal. Mudanças no provedor não devem neutralizar os cenários de falha para fazer o desafio passar.

## Dependências e licenças

O kit é uma base sintética criada para este exercício. As dependências mantêm suas próprias licenças. MinIO é compilado do commit `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`, referente ao release `RELEASE.2025-04-22T22-12-26Z`, de [minio/minio](https://github.com/minio/minio/tree/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e), licenciado sob [AGPL-3.0](https://github.com/minio/minio/blob/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e/LICENSE). O Dockerfile preserva o texto da licença na imagem e usa o código público correspondente; o kit não distribui binários MinIO.

A configuração do Eureka segue a [documentação oficial do Spring Cloud Netflix](https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html). O ambiente é um laboratório local de desenvolvimento, com uma instância por serviço; não demonstra disponibilidade de produção.


## Entrega e próxima etapa de avaliação

A solução deverá ser entregue em **código-fonte**, acompanhada dos artefatos que demonstrem o atendimento de todas as cinco histórias do cenário. Após a análise da entrega, você poderá ser convidado para a próxima etapa, em agenda combinada.

Essa etapa será uma **apresentação técnica de 90 minutos**. Prepare-se para explicar suas decisões, demonstrar os resultados, investigar um cenário relacionado à solução e realizar **pequenas alterações ao vivo no próprio código**. Tenha o ambiente local pronto para execução.

O uso de IA é permitido na preparação da entrega. Na sessão acompanhada, não será permitido utilizar IA, incluindo chat, agentes e sugestões generativas no editor; documentação, internet e autocomplete tradicional estarão disponíveis. A apresentação também faz parte da avaliação de domínio técnico e autonomia sobre a solução entregue.
