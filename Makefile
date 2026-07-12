SHELL := /bin/sh
WORKER_VENV_PYTHON := $(CURDIR)/worker/.venv/bin/python
AGENT_SERVICE_VENV_PYTHON := $(CURDIR)/agent-service/.venv/bin/python

MVN ?= mvn
ifneq ($(wildcard $(WORKER_VENV_PYTHON)),)
PYTHON ?= $(WORKER_VENV_PYTHON)
else
PYTHON ?= python3
endif
ifneq ($(wildcard $(AGENT_SERVICE_VENV_PYTHON)),)
AGENT_SERVICE_PYTHON ?= $(AGENT_SERVICE_VENV_PYTHON)
else
AGENT_SERVICE_PYTHON ?= $(PYTHON)
endif
NPM ?= npm
V5_CONTAINER_RUNTIME ?= docker
MAVEN_TEST_FLAGS ?= -Dapi.version=1.44 -Dv5.test.db.url=jdbc:postgresql://localhost:55432/agent_team_v5?stringtype=unspecified\&currentSchema=control_plane_v5,public -Dv5.test.db.user=agent_team -Dv5.test.db.password=agent_team

.PHONY: dev prod-up prod-reset doctor smoke-real test test-java test-python test-web test-agent-service gen-client

dev:
	docker compose up -d

prod-up:
	case "$(V5_CONTAINER_RUNTIME)" in \
	  apple) sh ./scripts/apple-container.sh build && sh ./scripts/apple-container.sh up ;; \
	  docker) docker compose --profile prod up -d --build ;; \
	  *) echo "不支持的 V5_CONTAINER_RUNTIME=$(V5_CONTAINER_RUNTIME)" >&2; exit 2 ;; \
	esac

prod-reset:
	sh ./scripts/prod-reset.sh

doctor:
	V5_CONTAINER_RUNTIME="$(V5_CONTAINER_RUNTIME)" sh ./scripts/doctor.sh

smoke-real:
	V5_CONTAINER_RUNTIME="$(V5_CONTAINER_RUNTIME)" sh ./scripts/smoke-real.sh

test: test-java test-python test-web

test-java:
	cd control-plane && $(MVN) $(MAVEN_TEST_FLAGS) verify

test-python: test-agent-service
	cd worker && $(PYTHON) -m pytest

test-web:
	cd workbench && $(NPM) test

test-agent-service:
	cd agent-service && $(AGENT_SERVICE_PYTHON) -m pytest

gen-client:
	cd workbench && $(NPM) run gen:api
