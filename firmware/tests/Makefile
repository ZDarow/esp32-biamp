# Host-тесты DSP BiAmp (обычный gcc/clang, без Arduino и ESP-IDF).
#
# Замораживают DSP-математику прошивки (биквад DF-IIT, кроссовер, линия
# задержки) против теоретического отклика, чтобы регрессии ловились в CI
# без железа.
#
# Использование:
#   make test-host          # собрать и прогнать все тесты
#   make test-host-clean    # удалить бинарники
#
# Без make (Windows):
#   gcc -O2 -Wall -o golden_biquad.exe golden_biquad.c -lm && golden_biquad.exe

CC ?= gcc
CFLAGS ?= -O2 -Wall
LDLIBS := -lm

# Суффикс исполняемого файла: .exe на Windows, пусто на Unix.
ifeq ($(OS),Windows_NT)
    EXEEXT := .exe
    RM     := del
else
    EXEEXT :=
    RM     := rm -f
endif

TESTS := golden_biquad golden_crossover golden_delay
BINS  := $(addsuffix $(EXEEXT),$(TESTS))

.PHONY: test-host test-host-clean $(TESTS)

test-host: $(TESTS)

$(TESTS): %: %.c
	$(CC) $(CFLAGS) -o $@$(EXEEXT) $< $(LDLIBS)
	@echo == $* ==
	@./$@$(EXEEXT)

test-host-clean:
	-$(RM) $(BINS)
