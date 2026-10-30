# Security

## Report a problem

Use GitHub's private "Report a vulnerability" form on this repository. Please don't open a public issue for a security problem.

## What this project does not fix

Disabling apps stops apps. It cannot change firmware. On the verified box, the firmware's own root services and its open debugging
access are still present after setup, so:

- Treat a budget TV box as untrusted. Put it on a guest network if your router has one.
- Don't use your main Google account on it if you can avoid it.
- Turn off network debugging on the box when you finish.

## Your data

Backups, undo records and logos stay on your computer in `~/.tvbox-revive`. The launcher has no internet permission.
