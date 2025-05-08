all: test install

.PHONY: clear
clear:
	rm -rf target

.PHONY: repl
repl: clear
	lein repl

.PHONY: test
test:
	lein test

install:
	lein install

lint:
	clj-kondo --lint src test

release: test
	lein release
