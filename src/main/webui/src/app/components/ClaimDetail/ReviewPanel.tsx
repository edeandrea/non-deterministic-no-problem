import config from '@app/config';
import { toLocalIsoDate } from '@app/utils/formatIncident';
import { isConflict } from '@app/utils/httpErrors';
import { Alert, Button, Checkbox, Flex, FlexItem, FormSelect, FormSelectOption, Spinner, TextArea, TextInput } from '@patternfly/react-core';
import axios from 'axios';
import * as React from 'react';

export interface ClaimReviewState {
  status: string;
  version: number;
  open: boolean;
  requested_items: string[];
  available_items: string[];
}

export type ReviewDecisionResult = 'submitted' | 'conflict';

export interface ReviewedClaim {
  summary?: string;
  incident_date?: string;
  incident_time?: string;
  location?: string;
  category?: string;
  sentiment?: string;
  inception_date?: string;
}

interface ReviewPanelProps {
  claimId: string;
  claim: ReviewedClaim;
  onDecisionResult: (result: ReviewDecisionResult) => void;
  // What to show instead of the form when no review is open (or it can't be loaded)
  fallback: React.ReactNode;
}

const CATEGORIES = ['Single vehicle', 'Multiple vehicle', 'Theft', 'Other'];

const DESCRIPTION = 'incident description';
const DATE = 'incident date';
const LOCATION = 'location';
const CATEGORY = 'category';

// The RFC 9457 `detail` the API answers with, so the reviewer sees why a request was refused
const problemDetail = (err: unknown, fallback: string): string => {
  const detail = axios.isAxiosError(err) ? err.response?.data?.detail : undefined;

  return (typeof detail === 'string' && detail) ? detail : fallback;
};

const blankToNull = (value: string): string | null => (value.trim() === '' ? null : value);

// The Summary tab's review form for a Pending Review claim: a claims processor edits the claim's details, checks "Ask customer" for
// what's still missing, and then either submits the claim or requests more information. The endpoint is asynchronous (it
// returns once the decision is published, not once the claim changes), so the parent is told the outcome and polls the
// claim itself. It replaces the Summary tab's read-only details, and only while the server says a review is open.
const ReviewPanel: React.FunctionComponent<ReviewPanelProps> = ({ claimId, claim, onDecisionResult, fallback }) => {
  const [review, setReview] = React.useState<ClaimReviewState | null>(null);
  const [asked, setAsked] = React.useState<Set<string>>(new Set());
  const [description, setDescription] = React.useState(claim.summary ?? '');
  const [incidentDate, setIncidentDate] = React.useState(claim.incident_date ?? '');
  const [incidentTime, setIncidentTime] = React.useState(claim.incident_time ?? '');
  const [location, setLocation] = React.useState(claim.location ?? '');
  const [category, setCategory] = React.useState(claim.category ?? '');
  const [sentiment, setSentiment] = React.useState(claim.sentiment ?? '');
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isRegenerating, setIsRegenerating] = React.useState(false);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    const controller = new AbortController();

    axios.get<ClaimReviewState>(config.backend_api_url + `/db/claims/${claimId}/review`, { signal: controller.signal })
      .then(response => setReview(response.data))
      .catch(err => {
        if (!axios.isCancel(err)) {
          setLoadError('Could not load the review');
        }
      });

    return () => controller.abort();
  }, [claimId]);

  const toggleAsked = (item: string, checked: boolean) => {
    setAsked(previous => {
      const next = new Set(previous);
      if (checked) {
        next.add(item);
      } else {
        next.delete(item);
      }
      return next;
    });
  };

  const handleFailure = (err: unknown, fallback: string) => {
    if (isConflict(err)) {
      onDecisionResult('conflict');
    } else {
      setError(problemDetail(err, fallback));
    }
  };

  const regenerate = () => {
    if (!review) {
      return;
    }

    setIsRegenerating(true);
    setError(null);

    axios.post<{ sentiment: string }>(config.backend_api_url + `/db/claims/${claimId}/sentiment`, { version: review.version })
      .then(response => setSentiment(response.data.sentiment))
      .catch(err => handleFailure(err, 'Could not regenerate the sentiment'))
      .finally(() => setIsRegenerating(false));
  };

  const hasAsked = asked.size > 0;

  const submit = () => {
    if (!review) {
      return;
    }

    setIsSubmitting(true);
    setError(null);

    const isDateAsked = asked.has(DATE);

    axios.post(config.backend_api_url + `/db/claims/${claimId}/review-decision`, {
      outcome: hasAsked ? 'NEEDS_INFORMATION' : 'READY',
      missing_items: Array.from(asked),
      fields: {
        description: asked.has(DESCRIPTION) ? null : blankToNull(description),
        incident_date: isDateAsked ? null : blankToNull(incidentDate),
        incident_time: isDateAsked ? null : blankToNull(incidentTime),
        location: asked.has(LOCATION) ? null : blankToNull(location),
        category: asked.has(CATEGORY) ? null : blankToNull(category),
      },
      version: review.version,
    })
      .then(() => onDecisionResult('submitted'))
      .catch(err => {
        setIsSubmitting(false);
        handleFailure(err, 'Could not submit the decision');
      });
  };

  if (loadError) {
    return <>{<Alert variant="danger" title={loadError} isInline />}{fallback}</>;
  }

  if (!review || !review.open) {
    return <>{fallback}</>;
  }

  const busy = isSubmitting || isRegenerating;

  const askCheckbox = (item: string) => (
    <Checkbox
      id={`ask-customer-${item.replace(/\s+/g, '-')}`}
      label="Ask customer"
      aria-label={`Ask customer for ${item}`}
      isChecked={asked.has(item)}
      isDisabled={busy}
      onChange={(_event, checked) => toggleAsked(item, checked)}
    />
  );

  const row = (label: string, item: string, input: React.ReactNode) => (
    <Flex alignItems={{ default: 'alignItemsFlexStart' }} flexWrap={{ default: 'nowrap' }}>
      <FlexItem style={{ minWidth: '9rem' }}><strong>{label}</strong></FlexItem>
      <FlexItem grow={{ default: 'grow' }}>{input}</FlexItem>
      <FlexItem>{askCheckbox(item)}</FlexItem>
    </Flex>
  );

  return (
    <Flex direction={{ default: 'column' }} className="review-panel padding-top-25" data-testid="review-panel" spaceItems={{ default: 'spaceItemsMd' }}>
      <FlexItem>
        <Alert variant="info" isInline title="This claim is ready for your review." data-testid="review-banner">
          Correct anything that's wrong, or check "Ask customer" for what's still missing.
        </Alert>
      </FlexItem>
      <FlexItem>
        {row('Date and time', DATE,
          <Flex>
            <FlexItem>
              <TextInput
                type="date"
                aria-label="Incident date"
                value={incidentDate}
                min={claim.inception_date}
                max={toLocalIsoDate(new Date())}
                isDisabled={busy || asked.has(DATE)}
                onChange={(_event, value) => setIncidentDate(value)}
              />
            </FlexItem>
            <FlexItem>
              <TextInput
                type="time"
                aria-label="Incident time"
                value={incidentTime}
                isDisabled={busy || asked.has(DATE)}
                onChange={(_event, value) => setIncidentTime(value)}
              />
            </FlexItem>
          </Flex>)}
      </FlexItem>
      <FlexItem>
        {row('Category', CATEGORY,
          <FormSelect
            aria-label="Category"
            value={category}
            isDisabled={busy || asked.has(CATEGORY)}
            onChange={(_event, value) => setCategory(value)}
          >
            <FormSelectOption key="" value="" label="Select a category" isPlaceholder />
            {CATEGORIES.map(label => <FormSelectOption key={label} value={label} label={label} />)}
          </FormSelect>)}
      </FlexItem>
      <FlexItem>
        {row('Description', DESCRIPTION,
          <TextArea
            aria-label="Description"
            value={description}
            isDisabled={busy || asked.has(DESCRIPTION)}
            onChange={(_event, value) => setDescription(value)}
          />)}
      </FlexItem>
      <FlexItem>
        {row('Location', LOCATION,
          <TextInput
            type="text"
            aria-label="Location"
            value={location}
            isDisabled={busy || asked.has(LOCATION)}
            onChange={(_event, value) => setLocation(value)}
          />)}
      </FlexItem>
      <FlexItem>
        <Flex alignItems={{ default: 'alignItemsCenter' }} flexWrap={{ default: 'nowrap' }}>
          <FlexItem style={{ minWidth: '9rem' }}><strong>Sentiment</strong></FlexItem>
          <FlexItem grow={{ default: 'grow' }} data-testid="review-sentiment">{sentiment || 'Not processed yet'}</FlexItem>
          <FlexItem>
            <Button variant="secondary" isDisabled={busy} onClick={regenerate}>
              {isRegenerating ? <><Spinner size="sm" aria-label="Regenerating" />&nbsp;Regenerating…</> : 'Regenerate'}
            </Button>
          </FlexItem>
        </Flex>
      </FlexItem>
      {error && (
        <FlexItem>
          <Alert variant="danger" title={error} isInline data-testid="review-error" />
        </FlexItem>
      )}
      <FlexItem>
        <Button variant="primary" isDisabled={busy} onClick={submit}>
          {hasAsked ? 'Request more information' : 'Submit claim for processing'}
        </Button>
      </FlexItem>
    </Flex>
  );
};

export { ReviewPanel };
