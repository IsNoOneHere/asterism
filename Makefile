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
MAVEN_TEST_FLAGS ?= -Dapi.version=1.44 -Dv5.test.db.url=jdbc:postgresql://localhost:55432/asterism?stringtype=unspecified\&currentSchema=control_plane_v5,public -Dv5.test.db.user=asterism -Dv5.test.db.password=asterism

.PHONY: dev prod-up prod-reset doctor smoke-real smoke-gitlab test test-java test-python test-web test-agent-service gen-client

dev:
	docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build

prod-up:
	case "$(V5_CONTAINER_RUNTIME)" in \
	  apple) ASTERISM_IMAGE_SOURCE=build sh ./scripts/apple-container.sh build && ASTERISM_IMAGE_SOURCE=build sh ./scripts/apple-container.sh up ;; \
	  docker) docker compose up -d --pull always ;; \
	  *) echo "不支持的 V5_CONTAINER_RUNTIME=$(V5_CONTAINER_RUNTIME)" >&2; exit 2 ;; \
	esac

prod-reset:
	sh ./scripts/prod-reset.sh

doctor:
	V5_CONTAINER_RUNTIME="$(V5_CONTAINER_RUNTIME)" sh ./scripts/doctor.sh

smoke-real:
	V5_CONTAINER_RUNTIME="$(V5_CONTAINER_RUNTIME)" sh ./scripts/smoke-real.sh

smoke-gitlab:
	V5_CONTAINER_RUNTIME="$(V5_CONTAINER_RUNTIME)" sh ./scripts/smoke-gitlab.sh

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
