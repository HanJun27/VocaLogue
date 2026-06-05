export interface GrammarFeedback {
  original: string;
  suggested: string;
  title: string;
  explanation: string;
}

export interface DialectMessage {
  id: string;
  role: 'ai' | 'user';
  text: string;
  translation?: string;
  timestamp: string;
  showTranslation?: boolean;
  feedback?: GrammarFeedback;
  isPlaying?: boolean;
}

export interface InterviewQuestion {
  text: string;
  translation: string;
  keywords: {
    phrase: string;
    suggested: string;
    explanation: string;
  }[];
}

export interface Scenario {
  id: string;
  title: string;
  tag: string;
  welcomeMessage: string;
  welcomeTranslation: string;
  questions: InterviewQuestion[];
  emoji?: string;
  difficulty?: number;
  description?: string;
}

export interface PronunciationScore {
  accuracy: number;
  fluency: number;
  grammar: number;
  overall: number;
  feedbackSummary: string;
}
