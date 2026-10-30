# Contributing

Issues and pull requests are welcome, especially new device profiles.

## Ground rules

- **Device profiles need proof.** Include real output from `benchmarks/func_check.py` and `benchmarks/measure.py`. Never copy another
  device's debloat list.
- **Numbers must be measured.** Do not claim a RAM or speed gain without a benchmark run on a real box.
- **Keep it reversible.** Disable, never uninstall. No root, no firmware changes and no boot partition changes.
- **No private data.** Run `tools/check_public.sh` first. Never commit logos, serial numbers, private addresses or logs.

## Add a design

See [docs/design-system/README.md](docs/design-system/README.md). Each design gets a new folder, and existing designs stay as they are.

## Check your change

```bash
tools/check_public.sh
./build.sh
```

To turn on the push check in your own clone, run `git config core.hooksPath .githooks`.
