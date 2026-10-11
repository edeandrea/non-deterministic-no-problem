<?php

/**
 * Gives the seeded claimants' Roundcube identities their names, so the mail they send goes out as
 * "Marty McFly <marty.mcfly@email.com>" rather than a bare address.
 *
 * The names come from $config['parasol_identities'] (config/parasol-identities.php), keyed by address.
 * - user_create: names the first identity of a user Roundcube is creating.
 * - login_after: on every login, fills in the name of any mapped identity whose name is empty, so users that existed
 *   before the plugin get it too. A name that is already set (e.g. changed in Settings > Identities) is never touched.
 * An address that is not in the map is left alone, and an absent or empty map does nothing.
 */
class parasol_identities extends rcube_plugin
{
	public $task = 'login|mail|settings';

	public function init()
	{
		$this->add_hook('user_create', [$this, 'user_create']);
		$this->add_hook('login_after', [$this, 'login_after']);
	}

	public function user_create($args)
	{
		$name = $this->name_for($args['user_email'] ?? '');

		if ($name !== null) {
			$args['user_name'] = $name;
		}

		return $args;
	}

	public function login_after($args)
	{
		$user = rcmail::get_instance()->user;

		if ($user) {
			foreach ($user->list_identities() as $identity) {
				$name = $this->name_for($identity['email'] ?? '');

				if (($name !== null) && (trim($identity['name'] ?? '') === '')) {
					$user->update_identity($identity['identity_id'], ['name' => $name]);
				}
			}
		}

		return $args;
	}

	private function name_for($email)
	{
		$names = rcmail::get_instance()->config->get('parasol_identities');
		$wanted = strtolower(trim($email));
		$found = null;

		if (is_array($names) && ($wanted !== '')) {
			foreach ($names as $address => $name) {
				if (strtolower($address) === $wanted) {
					$found = $name;
				}
			}
		}

		return $found;
	}
}
