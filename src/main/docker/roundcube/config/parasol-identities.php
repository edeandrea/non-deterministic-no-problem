<?php

/**
 * The display name of each seeded claimant, keyed by login address (compared case-insensitively).
 * Read by the parasol_identities plugin. Keep it in step with the claimants in src/main/resources/db/migration/V2__seed_claims.sql.
 * An address that is not listed is left alone.
 */
$config['parasol_identities'] = [
	'marty.mcfly@email.com' => 'Marty McFly',
	'JAnderson@mail.com' => 'John T. Anderson',
	'jane_doe@email.com' => 'Jane Doe',
	'domt@email.com' => 'Dominic Toretto',
	'bettercallsaul@email.com' => 'Saul Goodman',
	'tylannister@email.com' => 'Tyrion Lannister',
];
