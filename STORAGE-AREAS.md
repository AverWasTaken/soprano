# Effortless storage areas

Open the Soprano menu, choose **Effortless → Storage**, then select an entry to edit it. An empty hand and two corners in Effortless mode still records a new storage area.

Each entry has a name, an **Input**, **Output**, or **Both** role, and an item rule:

- **All items** accepts any eligible item.
- **Only** accepts the listed blocks/items or item tags.
- **Except** accepts everything other than the listed items or tags.

Use item IDs separated by commas or spaces, such as `stone, diamond, oak_log`, or item tags such as `#minecraft:logs`. The `minecraft:` prefix is optional. Starting to type a list changes **All items** to **Only** automatically. Normal deposit protections for tools and worn equipment still apply.

The list is ordered by priority. **Higher priority** and **Lower priority** move an entry. Deposits and material fetches try eligible areas in this order, continuing to the next area if a storage is full, short of items, or cannot be reached. Items with no accepting output storage remain carried. Existing full-storage stop/retry settings apply after the eligible route has been tried.

**Merge next** combines adjacent entries with matching roles and item rules. It retains their exact areas, including separate regions; containers in the gap are excluded. To combine entries with different rules, edit their roles and rules first. Inside a merged entry, **Next area** selects the area whose corners you are editing. **Remove area** removes just that region, while **Remove storage** removes the entire entry and asks for a second confirmation.

**Save** stores the name, rules and corners. Priority and merge actions save the current draft before acting. **Back** discards an unsaved draft. Storage definitions and priority order are saved with the world/dimension's Effortless queue; older storage files load as unfiltered entries.

Changes apply to the next trip. A running transfer uses a snapshot of its original destinations and rules.
