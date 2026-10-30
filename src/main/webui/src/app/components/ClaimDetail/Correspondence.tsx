import config from '@app/config';
import { ClaimImage } from '@app/utils/claimImages';
import { CorrespondenceMessage, initiallyExpanded, showsOriginalContent, unlinkedImages } from '@app/utils/correspondence';
import { Accordion, AccordionContent, AccordionItem, AccordionToggle, Grid, GridItem, Text, TextContent, TextVariants } from '@patternfly/react-core';
import axios from 'axios';
import * as React from 'react';
import { ImageCarousel } from '../ImageCarousel/ImageCarousel';

interface CorrespondenceProps {
  claimId: string;
  claim: { subject?: string; body?: string };
  originalImages: ClaimImage[];
}

const CLAIM_TOGGLE = 'claim-toggle1';
const OTHER_IMAGES_TOGGLE = 'other-images-toggle';

// The claim's emails in order: the customer's and the replies the intake sent, each with the photos it carried. A claim with
// no recorded emails (a seeded one) shows its original content as a single item, as before the intake existed. It reads the
// emails once, when it mounts: ClaimDetail mounts it each time the Documents tab opens, keyed by the claim.
const Correspondence: React.FunctionComponent<CorrespondenceProps> = ({ claimId, claim, originalImages }) => {
  const [messages, setMessages] = React.useState<CorrespondenceMessage[]>([]);
  const [expanded, setExpanded] = React.useState<string[]>([CLAIM_TOGGLE]);

  React.useEffect(() => {
    const controller = new AbortController();

    axios.get<CorrespondenceMessage[]>(config.backend_api_url + `/db/claims/${claimId}/correspondence`, { signal: controller.signal })
      .then(response => {
        setMessages(response.data);
        setExpanded(showsOriginalContent(response.data) ? [CLAIM_TOGGLE, ...initiallyExpanded(response.data)] : initiallyExpanded(response.data));
      })
      .catch(err => {
        if (!axios.isCancel(err)) {
          console.error(err);
        }
      });

    return () => controller.abort();
  }, [claimId]);

  const toggle = (id: string) => setExpanded(current => current.includes(id) ? current.filter(item => item !== id) : [...current, id]);

  const showsOriginal = showsOriginalContent(messages);
  const images = unlinkedImages(originalImages, messages);

  return (
    <Accordion isBordered asDefinitionList={false} className='padding-top-25'>
      {showsOriginal && (
        <AccordionItem>
          <AccordionToggle onClick={() => toggle(CLAIM_TOGGLE)} isExpanded={expanded.includes(CLAIM_TOGGLE)} id={CLAIM_TOGGLE}>
            Original claim content
          </AccordionToggle>
          <AccordionContent id="claim-content1" isHidden={!expanded.includes(CLAIM_TOGGLE)}>
            <TextContent>
              <Text component={TextVariants.h3}>Subject:</Text>
              {claim.subject}
              <Text component={TextVariants.h3}>Body:</Text>
              <div className='display-linebreak'>{claim.body}</div>
              <Text component={TextVariants.h3}>Attached images:</Text>
              {(images.original.length > 0)
                ? <Grid><GridItem span={4}><ImageCarousel images={images.original} /></GridItem></Grid>
                : 'No images attached'}
            </TextContent>
          </AccordionContent>
        </AccordionItem>
      )}
      {messages.map(message => {
        const id = `message-${message.id}`;
        const sender = (message.direction === 'INBOUND') ? (message.sender ?? 'Customer') : 'Parasol claims';

        return (
          // AccordionItem renders only a fragment, so it would drop the test attributes: they go on a wrapper, which
          // also scopes a Playwright locator to one message
          <div key={id} data-testid="correspondence-message" data-direction={message.direction}>
            <AccordionItem>
              <AccordionToggle onClick={() => toggle(id)} isExpanded={expanded.includes(id)} id={`${id}-toggle`}>
                {sender} · {new Date(message.date).toLocaleString()}
              </AccordionToggle>
              <AccordionContent id={`${id}-content`} isHidden={!expanded.includes(id)}>
                <TextContent>
                  <Text component={TextVariants.h3}>Subject:</Text>
                  {message.subject}
                  <div className='display-linebreak'>{message.text}</div>
                  {((message.images?.length ?? 0) > 0) && (
                    <>
                      <Text component={TextVariants.h3}>Attached images:</Text>
                      <Grid><GridItem span={4}><ImageCarousel images={message.images ?? []} /></GridItem></Grid>
                    </>
                  )}
                </TextContent>
              </AccordionContent>
            </AccordionItem>
          </div>
        );
      })}
      {(images.other.length > 0) && (
        <AccordionItem>
          <AccordionToggle onClick={() => toggle(OTHER_IMAGES_TOGGLE)} isExpanded={expanded.includes(OTHER_IMAGES_TOGGLE)} id={OTHER_IMAGES_TOGGLE}>
            Other attached images
          </AccordionToggle>
          <AccordionContent id="other-images-content" isHidden={!expanded.includes(OTHER_IMAGES_TOGGLE)}>
            <Grid><GridItem span={4}><ImageCarousel images={images.other} /></GridItem></Grid>
          </AccordionContent>
        </AccordionItem>
      )}
    </Accordion>
  );
};

export { Correspondence };
