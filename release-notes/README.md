One file per upcoming release, named after its full version (`1.1.4.md`). The release workflow appends it to the release body when that tag is pushed. Nightlies and anyversion builds use the whole version too: `1.1.7-nightly.1.md`, `1.1.7-nightly.1-anyversion.md`.

Keep it short and readable for players: a few bullets of what's new or fixed, each with the commit it came from.

```
### What's new and fixed

- Sprinting no longer cuts out when climbing single blocks (abc1234)
```
