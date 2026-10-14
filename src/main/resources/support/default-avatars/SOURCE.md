# Default support avatar source

Eight individual fictional adult portraits were generated with image_gen.imagegen on 2026-10-09. They are default display resources, not a claim about a staff member's real identity. The explicit creation briefs choose MALE/FEMALE pools; UNSPECIFIED combines all eight. Full prompts, generated output IDs and root visual checks are preserved in manifest.json. No remote sample photos were imported.

This release uses a deterministic technical export of the reviewed originals: 512 x 512, no crop or enlargement, sRGB JPEG quality90 with 4:4:4 chroma. No scene, person, expression, clothing or background was edited. Original PNGs remain preserved in the version1 task bundle; each entry records the original and exported SHA256 and export recipe.

All eight JPEGs were decoded and measured against the current preview's explicit limits: image/jpeg permitted, maxBytes1048576, maxPixels1000000. Backend validation, storage/DB persistence and independent runtime acceptance remain required.

- male-01: 512 x 512; 45461 bytes; SHA256 4643b4848ca34a58e7ea7f6077c9a185185595c1ea8bd5f6837ed9c1bff466a0
- male-02: 512 x 512; 37773 bytes; SHA256 d3b4c4eaaeeb87435b68b57ff9acf011acfa333dc6f97ad616aee7356bc7d9fa
- male-03: 512 x 512; 50544 bytes; SHA256 a6d86a04107e1660ff97ad5740a2177d37f592a96b0d6bd3f79b19786a9f8461
- male-04: 512 x 512; 46489 bytes; SHA256 48aa15e9b7b711e4d5ae584d87d8cd64dfd1d346e6af847827a24c1f46e9444d
- female-01: 512 x 512; 42868 bytes; SHA256 faefe442ce32218fcfce5bd918cc6be450faaea51c4e78fa030e19907a140323
- female-02: 512 x 512; 40365 bytes; SHA256 9e6f7a39343adf3bf833a3b401c3e6987cad776f5646a1e8095ca3743444f050
- female-03: 512 x 512; 44064 bytes; SHA256 ba0db7ac723861b889105dabdf234378caa0eb3330fa90aa98e96a9cb5f11312
- female-04: 512 x 512; 51704 bytes; SHA256 184588536b69bed58372298845763311fe6ab4d5abf18faa9d566a28055e137d
