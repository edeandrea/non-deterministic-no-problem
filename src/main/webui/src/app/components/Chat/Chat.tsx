import config from '@app/config';
import { faCommentDots, faPaperPlane } from '@fortawesome/free-regular-svg-icons';
import { faPlusCircle } from '@fortawesome/free-solid-svg-icons';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { Button, Card, CardBody, CardHeader, Flex, FlexItem, Grid, GridItem, Panel, PanelMain, PanelMainBody, Stack, StackItem, Text, TextArea, TextContent, TextVariants, Tooltip } from '@patternfly/react-core';
import * as React from 'react';
import orb from '@app/assets/bgimages/orb.svg';
import userAvatar from '@app/assets/bgimages/avatar-user.svg';

const Chat: React.FunctionComponent<{ claimSummary: string, claimId: string, inceptionDate: Date }> = ({ claimSummary, claimId, inceptionDate }) => {

    type Query = string;
    type Answer = string[];
    type Message = Query | Answer;
    type MessageHistory = Message[];

    const [queryText, setQueryText] = React.useState<Query>('');
    const [answerText, setAnswerText] = React.useState<Answer>([' Hi! I am Parasol Assistant. How can I help you today?']);
    const [messageHistory, setMessageHistory] = React.useState<MessageHistory>([]);

    const wsUrl = config.backend_api_url.replace(/^http/, 'ws').replace(/\/api$/, '') + '/_chat/routes';

    const sessionRef = React.useRef<ChatScopesSession | null>(null);
    const clientRef = React.useRef<ChatScopesClient | null>(null);
    const chatBotAnswer = document.getElementById('chatBotAnswer');

    const createSession = async (client: ChatScopesClient): Promise<ChatScopesSession> => {
        return client.builder()
            .messageHandler((message: string) => {
                setAnswerText([message]);
            })
            .errorHandler((error: string) => {
                console.error('Chat error:', error);
                setAnswerText(['An error occurred. Please try again.']);
            })
            .thinkingHandler((message: string) => {
                console.log('Thinking: ', message);
            })
            .connect('chat');
    };

    React.useEffect(() => {
        let cancelled = false;
        const client = new ChatScopesClient();

        const initSession = async () => {
            await client.open(wsUrl);
            if (cancelled) return;
            clientRef.current = client;
            sessionRef.current = await createSession(client);
        };

        initSession().catch((err) => {
            if (!cancelled) {
                console.error('Failed to initialize chat session:', err);
            }
        });

        return () => {
            cancelled = true;
            client.websocket?.close();
            clientRef.current = null;
            sessionRef.current = null;
        };
    }, []);

    React.useEffect(() => {
        if (chatBotAnswer) {
            chatBotAnswer.scrollTop = chatBotAnswer.scrollHeight;
        }
    }, [answerText]);


    const sendQueryText = () => {
        if (!sessionRef.current) {
            return;
        }

        if (queryText === '') {
            setAnswerText(['Please enter a query...']);
            return;
        }

        const previousAnswer = answerText;
        setMessageHistory([...messageHistory, previousAnswer, queryText]);
        setQueryText('');
        setAnswerText([]);

        sessionRef.current.sendData({
            query: {
                claimId: claimId,
                query: queryText,
                claim: claimSummary,
                inceptionDate: inceptionDate
            }
        }).catch((err) => {
            console.error('Failed to send query:', err);
            setAnswerText(['An error occurred. Please try again.']);
        });
    };

    const resetMessageHistory = () => {
        setMessageHistory([]);
        setAnswerText([' Hi! I am Parasol Assistant. How can I help you today?']);

        if (clientRef.current) {
            createSession(clientRef.current)
                .then((session) => {
                    sessionRef.current = session;
                })
                .catch((err) => {
                    console.error('Failed to create new chat session:', err);
                });
        }
    };

    return (
        <Card isRounded className='chat-card'>
            <CardHeader className='chat-card-header'>
                <TextContent>
                    <Text component={TextVariants.h3} className='chat-card-header-title'><FontAwesomeIcon icon={faCommentDots} />&nbsp;Parasol Assistant</Text>
                </TextContent>
            </CardHeader>
            <CardBody className='chat-card-body'>
                <Stack>
                    <StackItem isFilled className='chat-bot-answer' id='chatBotAnswer'>
                        <TextContent>
                            {messageHistory.map((message, index) => {
                                const renderMessage = () => {
                                    if (typeof message === 'string') { // If the message is a query
                                        return <Grid className='chat-item'>
                                            <GridItem span={1} className='grid-item-orb'>
                                                <img src={userAvatar} className='user-avatar' />
                                            </GridItem>
                                            <GridItem span={11}>
                                                <Text component={TextVariants.p} className='chat-question-text'>{message}</Text>
                                            </GridItem>
                                        </Grid>
                                    } else { // If the message is a response
                                        return <Grid className='chat-item'>
                                            <GridItem span={1} className='grid-item-orb'>
                                                <img src={orb} className='orb' />
                                            </GridItem>
                                            <GridItem span={11}>
                                                <Text component={TextVariants.p} className='chat-answer-text'>{message.join("")}</Text>
                                            </GridItem>
                                        </Grid>
                                    }
                                };

                                return (
                                    <React.Fragment key={index}>
                                        {renderMessage()}
                                    </React.Fragment>
                                );
                            })}
                            <Grid className='chat-item'>
                                <GridItem span={1} className='grid-item-orb'>
                                    <img src={orb} className='orb' />
                                </GridItem>
                                <GridItem span={11}>
                                    <Text component={TextVariants.p} className='chat-answer-text'>{answerText.join("") != "" && answerText.join("")}</Text>
                                </GridItem>
                            </Grid>
                        </TextContent>
                    </StackItem>
                    <StackItem className='chat-input-panel'>
                        <Panel variant="raised">
                            <PanelMain>
                                <PanelMainBody className='chat-input-panel-body'>
                                    <TextArea
                                        value={queryText}
                                        type="text"
                                        onChange={(_event, queryText) => setQueryText(queryText)}
                                        aria-label="query text input"
                                        placeholder='Ask me anything...'
                                        onKeyPress={event => {
                                            if (event.key === 'Enter') {
                                                event.preventDefault();
                                                sendQueryText();
                                            }
                                        }}
                                    />
                                    <Flex>
                                        <FlexItem>
                                            <Tooltip
                                                content={<div>Start a new chat</div>}
                                            >
                                                <Button variant="link" onClick={resetMessageHistory} aria-label='StartNewChat'><FontAwesomeIcon icon={faPlusCircle} /></Button>
                                            </Tooltip>
                                        </FlexItem>
                                        <FlexItem align={{ default: 'alignRight' }}>
                                            <Tooltip
                                                content={<div>Send your query</div>}
                                            >
                                                <Button variant="link" onClick={sendQueryText} aria-label='SendQuery'><FontAwesomeIcon icon={faPaperPlane} /></Button>
                                            </Tooltip>
                                        </FlexItem>
                                    </Flex>
                                </PanelMainBody>
                            </PanelMain>
                        </Panel>
                    </StackItem>
                    <StackItem>
                        <TextContent>
                            <Text className='chat-disclaimer'>Powered by AI. It may display inaccurate info, so please double-check the responses.</Text>
                        </TextContent>
                    </StackItem>
                </Stack>
            </CardBody>
        </Card >
    );
}

export { Chat };
