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

toc-install:
	npm install --save markdown-toc

toc-build:
	node_modules/.bin/markdown-toc -i README.md
