# Rota Clara — desafio técnico PDV

Implementação das cinco histórias da Rede Brisa 27: necessidade de reposição,
recebimento idempotente, histórico de entrega parcial, emissão documental
recuperável e cache consistente. Todos os dados e documentos são fictícios.

## Executar

Pré-requisitos: Docker Engine, Docker Compose v2 e Bash; no Windows, use WSL.
Reserve 8 GB de memória para o laboratório e acesso à internet no primeiro build.
Java, Maven, Node, Go e Python não precisam estar instalados no host.

```bash
git clone https://github.com/FlavysonFelipe314/teste-tecnico-pdv.git
cd teste-tecnico-pdv
docker compose up --build -d
docker compose exec -T fiscal-simulado python - < scripts/ready.py
```

Aguarde `LAB READY` e abra **http://localhost:13000**. Selecione Gestor para criar
pedidos e Operador para confirmar recebimentos. Tokens locais da API:
`gestor-local` e `operador-local`; não representam autenticação de produção.

## Validar

```bash
bash scripts/validate-all.sh
bash scripts/test-ui.sh
```

O primeiro comando verifica H01–H05 com banco e dependências reais em recursos
isolados, preservando pedidos e cenários do laboratório principal. Esperado:
`VALIDAÇÃO H01–H05 OK`. O segundo verifica a interface no Chromium com respostas
determinísticas da API; esperado: `UI OK`.

Os scripts Docker de validação usam rede host e foram executados em Linux.
Consulte [o roteiro](docs/TESTES.md) para requisitos, testes individuais, cenários
manuais e instruções de reset. `smoke.py` cria pedido e movimenta estoque; execute
somente quando quiser alterar a base de demonstração.

## Solução e evidências

| História | Implementação |
|---|---|
| H01 | Necessidade `max(0, meta - saldo)` e validação fresca no backend |
| H02 | Bloqueio transacional do pedido, repetição idêntica e conflito divergente |
| H03 | Solicitado imutável, recebido e faltante separados |
| H04 | Fila persistida no MySQL, worker, reconciliação por chave e XML privado |
| H05 | Redis por loja/versão, atualização transacional e fallback ao banco |

- [ENTREGA.md](ENTREGA.md): diagnóstico, decisões, alterações, evidências e limites das cinco histórias.
- [docs/TESTES.md](docs/TESTES.md): reprodução completa, pelo terminal e pelo painel.
- [Resultados do backend](docs/evidencias/h01-h05-testes.txt).
- [Resultados da integração real](docs/evidencias/h01-h05-integracao.json).
- [Resultado da interface](docs/evidencias/h04-h05-interface.txt).
- [Enunciado original](docs/DESAFIO.md) e [guia técnico da base original](docs/GUIA-BASE.md).

A documentação distingue testes com dependências simuladas das integrações reais.
O uso de IA está registrado em ENTREGA.md.

## Validação contínua

O workflow `.github/workflows/ci.yml` executa testes do backend em H2 e compila o
Admin a cada push/pull request. A integração com MySQL, Redis, fiscal e MinIO
reais continua disponível pelo script completo; não é substituída por esse CI.

## Estrutura

```text
admin/        React, TypeScript e proxy Nginx
core/         Java 21, Spring Boot, reposição, documentos, Flyway e testes
eureka/       Registro de serviços já presente no kit
fiscal/       Provedor fiscal controlado em Python/SQLite
minio/        Build do armazenamento S3 privado
scripts/      Prontidão, smoke e validadores
docs/         Roteiro, enunciado e evidências
compose.yaml  Ambiente reproduzível
```

O recebimento é confirmado independentemente da emissão documental. O painel
acompanha a emissão automaticamente; o XML aparece quando estiver persistido.
RabbitMQ permanece no kit, enquanto esta solução usa o MySQL como fila durável.
O cache conserva uma leitura pequena de versão por GET; a redução medida é nas
consultas de estoque/produtos, sem promessa artificial de latência.

## Manutenção

```bash
# Atualizar a aplicação preservando dados:
docker compose up --build -d core admin
# Parar preservando volumes:
docker compose down
# Logs:
docker compose logs --tail 100 core
```

Para resetar, `docker compose down --volumes` **apaga todos os dados locais**.
Depois suba o ambiente novamente. A migração V2 evolui bancos existentes sem
alterar V1; quantidades solicitadas perdidas antes da correção não são inventadas.

## Origem e dependências

Base fornecida por [easypdverp/desafio-pdv-publico](https://github.com/easypdverp/desafio-pdv-publico).
As credenciais presentes no Compose são exclusivamente sintéticas do laboratório.
Não há integração com AWS, SEFAZ ou serviços fiscais reais.

MinIO é compilado do commit público fixado no Dockerfile e mantém sua licença
AGPL-3.0. Referências e atribuições estão no
[guia da base](docs/GUIA-BASE.md#dependências-e-licenças); as demais dependências
mantêm suas licenças. Esta entrega não inclui binários MinIO, imagens Docker,
volumes, dependências instaladas ou dados reais.
