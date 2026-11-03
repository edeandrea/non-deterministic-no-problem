import { Label, Tooltip } from '@patternfly/react-core';
import * as React from 'react';

const COLORS = {
  'Processed': 'green',
  'New': 'blue',
  'Denied': 'red',
  'In Process': 'gold',
  'Pending Information': 'orange',
  'Pending Review': 'purple',
};

// Why a claim in these statuses can't be acted on yet: the email intake hasn't completed
const INTAKE_HINTS = {
  'Pending Review': 'Intake is not complete: waiting for a claims processor to review it. No other action can be taken yet.',
  'Pending Information': 'Intake is not complete: waiting for the customer to provide more information. No other action can be taken yet.',
};

interface StatusLabelProps {
  status?: string;
}

const StatusLabel: React.FunctionComponent<StatusLabelProps> = ({ status }) => {
  const label = <Label color={COLORS[String(status)] || 'default'}>{status}</Label>;
  const hint = INTAKE_HINTS[String(status)];

  return hint ? <Tooltip content={hint}><span tabIndex={0}>{label}</span></Tooltip> : label;
};

export { StatusLabel };
