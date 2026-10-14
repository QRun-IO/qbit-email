/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.email;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;

/*******************************************************************************
 ** Runs a streamed-ETL table or record action the way the UI does: the
 ** review screen, then the result.
 *******************************************************************************/
public final class ProcessRunner
{
   /*******************************************************************************
    ** Review and result lines, as shown.
    *******************************************************************************/
   public record Lines(List<String> review, List<String> result)
   {
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ProcessRunner()
   {
   }

   /*******************************************************************************
    ** Run on some record ids.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   public static Lines run(String process, Serializable... ids) throws Exception
   {
      return (runWithValues(process, java.util.Map.of(), ids));
   }

   /*******************************************************************************
    ** Run With Values.
    *******************************************************************************/
   public static Lines runWithValues(
         String process, java.util.Map<String, Serializable> values, Serializable... ids)
         throws Exception
         {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(process);
      input.addValue("recordsParam", "recordIds");
      input.addValue(
            "recordIds", Arrays.stream(ids).map(String::valueOf).collect(Collectors.joining(",")));
      values.forEach(input::addValue);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      RunProcessOutput review = new RunProcessAction().execute(input);
      if(process.equals(com.kingsrook.qbits.email.processes.EmailProcesses.SEND_TEST))
      {
         RunProcessInput supplied = new RunProcessInput();
         supplied.setProcessName(process);
         supplied.setProcessUUID(review.getProcessUUID());
         supplied.setStartAfterStep("input");
         supplied.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
         values.forEach(supplied::addValue);
         review = new RunProcessAction().execute(supplied);
      }
      if(review.getException().isPresent())
      {
         throw (review.getException().get());
      }
      List<ProcessSummaryLineInterface> reviewLines =
            (List<ProcessSummaryLineInterface>)
                  review.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);

      RunProcessInput go = new RunProcessInput();
      go.setProcessName(process);
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      if(result.getException().isPresent())
      {
         throw (result.getException().get());
      }
      List<ProcessSummaryLineInterface> resultLines =
            (List<ProcessSummaryLineInterface>)
                  result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      return (new Lines(text(reviewLines, false), text(resultLines, true)));
   }

   /*******************************************************************************
    ** Lines as text.
    *******************************************************************************/
   private static List<String> text(List<ProcessSummaryLineInterface> lines, boolean past)
   {
      return (lines == null
            ? List.of()
            : lines.stream()
                  .map(
                        line ->
                        {
                           if(line instanceof ProcessSummaryLine summary)
                           {
                              summary.pickMessage(past);
                              return ((summary.getCount() == null ? "" : summary.getCount() + " ")
                                    + summary.getMessage());
                           }
                           return (String.valueOf(line));
                        })
                  .toList());
   }
}
