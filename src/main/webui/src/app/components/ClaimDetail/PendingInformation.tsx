import config from '@app/config';
import { Alert, List, ListItem } from '@patternfly/react-core';
import axios from 'axios';
import * as React from 'react';
import { ClaimReviewState } from './ReviewPanel';

interface PendingInformationProps {
  claimId: string;
}

// Read-only: what the customer was asked for while the claim waits for their reply. It has no actions, since the next
// step belongs to the customer's reply.
const PendingInformation: React.FunctionComponent<PendingInformationProps> = ({ claimId }) => {
  const [items, setItems] = React.useState<string[]>([]);

  React.useEffect(() => {
    const controller = new AbortController();

    axios.get<ClaimReviewState>(config.backend_api_url + `/db/claims/${claimId}/review`, { signal: controller.signal })
      .then(response => setItems(response.data.requested_items))
      .catch(err => {
        if (!axios.isCancel(err)) {
          console.error(err);
        }
      });

    return () => controller.abort();
  }, [claimId]);

  if (items.length === 0) {
    return null;
  }

  return (
    <Alert variant="warning" isInline title="Waiting for the customer to provide:" className="padding-bottom-25" data-testid="pending-information">
      <List>
        {items.map(item => <ListItem key={item}>{item}</ListItem>)}
      </List>
    </Alert>
  );
};

export { PendingInformation };
